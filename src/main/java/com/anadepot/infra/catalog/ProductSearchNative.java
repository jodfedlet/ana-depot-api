package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductSearch;
import com.anadepot.core.catalog.model.ProductSort;
import com.anadepot.core.catalog.model.SearchDocument;
import com.anadepot.core.catalog.repository.ProductSearchIndex;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE ONLY native SQL of the application's catalog code, and the only place that knows PostgreSQL full-text operators.
 *
 * <p>Technical justification (AGENTS.md: native SQL is a last resort): JPQL/Criteria and Hibernate 7.4 have no tsvector type, no {@code @@} match
 * operator, no {@code ts_rank_cd}/{@code setweight}/{@code to_tsquery} (verified: no such functions exist in hibernate-core), so ranked full-text search is not
 * expressible otherwise. It is executed through the JPA {@link EntityManager} (no Spring JDBC helpers, no raw database driver access). All SQL text is built from fixed fragments;
 * every value, including the tsquery, is a BOUND named parameter - user text never reaches the SQL string. All public catalog searches (with or without a
 * text) run here so the filter rules exist once.
 *
 * <p>Index maintenance ({@link #index}) is called by the core catalog use cases inside the transaction of the change.
 */
@Component
@Transactional
class ProductSearchNative implements ProductSearchIndex {

	/** Result sets deeper than this are refused: deep offsets are expensive and no screen needs them (the storefront paginates by 12-24). */
	static final int MAX_OFFSET = 10_000;
	private static final int TIMEOUT_MS = 5_000;

	@PersistenceContext
	private EntityManager em;

	@Override
	public void index(UUID productId, SearchDocument d) {
		em.createNativeQuery("""
				update products set search_vector =
				      setweight(to_tsvector('simple', cast(:names as text)), 'A')
				   || setweight(to_tsvector('simple', cast(:refs as text)), 'B')
				   || setweight(to_tsvector('simple', cast(:descr as text)), 'C')
				   || setweight(to_tsvector('simple', cast(:attrs as text)), 'D')
				where id = :id
				""")
				.setParameter("names", d.names()).setParameter("refs", d.references()).setParameter("descr", d.description()).setParameter("attrs", d.attributes())
				.setParameter("id", productId).executeUpdate();
	}

	PageResult<UUID> search(ProductSearch s, PageRequest page) {
		if ((long) page.page() * page.size() > MAX_OFFSET) {
			throw new DomainRuleViolation("page_out_of_range", "Results beyond position " + MAX_OFFSET + " are not available: refine the search");
		}
		var params = new LinkedHashMap<String, Object>();
		var where = new StringBuilder("""
				 from products p
				where p.status = 'ACTIVE'
				  and exists (select 1 from categories c where c.id = p.category_id and c.active)
				  and exists (select 1 from product_prices x where x.product_id = p.id and x.active)
				""");
		boolean text = !s.query().isEmpty();
		if (text) {
			where.append(" and p.search_vector @@ to_tsquery('simple', cast(:tsq as text))\n");
			params.put("tsq", s.query().tsQuery());
		}
		if (s.categoryId() != null) {
			where.append(" and p.category_id = :categoryId\n");
			params.put("categoryId", s.categoryId());
		}
		if (!s.brands().isEmpty()) {
			where.append(" and lower(p.brand) in (:brands)\n");
			params.put("brands", s.brands().stream().map(b -> b.strip().toLowerCase(Locale.ROOT)).toList());
		}
		if (s.currency() != null || s.wholesaleOnly() || s.minPrice() != null || s.maxPrice() != null) {
			// every price condition must hold on the SAME price row (one currency): amounts of other currencies never take part
			where.append(" and exists (select 1 from product_prices pp where pp.product_id = p.id and pp.active");
			if (s.currency() != null) {
				where.append(" and pp.currency = :currency");
				params.put("currency", s.currency().name());
			}
			if (s.wholesaleOnly()) {
				where.append(" and pp.wholesale_enabled");
			}
			if (s.minPrice() != null) {
				where.append(" and coalesce(pp.sale_price, pp.retail_price) >= :minPrice");
				params.put("minPrice", s.minPrice());
			}
			if (s.maxPrice() != null) {
				where.append(" and coalesce(pp.sale_price, pp.retail_price) <= :maxPrice");
				params.put("maxPrice", s.maxPrice());
			}
			where.append(")\n");
		}
		if (s.inStockOnly()) {
			where.append(" and exists (select 1 from inventory_items i where i.product_id = p.id and (not i.tracked or i.on_hand - i.reserved > 0))\n");
		}
		var byKey = new LinkedHashMap<String, List<String>>();
		s.attributes().forEach(a -> byKey.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(a.optionCode()));
		int n = 0;
		for (var entry : byKey.entrySet()) {
			where.append(" and exists (select 1 from product_attribute_values v join category_attribute_definitions d on d.id = v.attribute_id")
					.append(" join category_attribute_options o on o.id = v.option_id and o.attribute_id = d.id")
					.append(" where v.product_id = p.id and d.attr_key = :ak").append(n).append(" and o.code in (:ac").append(n).append(") and o.active)\n");
			params.put("ak" + n, entry.getKey());
			params.put("ac" + n, entry.getValue());
			n++;
		}

		var select = new StringBuilder("select p.id").append(where).append(" order by ");
		switch (s.sort()) {
			case RELEVANCE -> {
				// an exact SKU / variant SKU match comes first, then ts_rank_cd over the weighted vector (A names > B refs > C description > D attributes)
				select.append("""
						(case when lower(p.sku) = :exact or exists (select 1 from product_variants pv where pv.product_id = p.id and lower(pv.sku) = :exact) then 0 else 1 end),
						 ts_rank_cd(p.search_vector, to_tsquery('simple', cast(:tsq as text)), 32) desc, p.created_at desc, p.id""");
				params.put("exact", s.query().exact());
			}
			case NEWEST -> select.append("p.created_at desc, p.id");
			case NAME -> select.append("p.sort_name, p.id");
			case PRICE_ASC, PRICE_DESC -> {
				select.append("(select coalesce(sp.sale_price, sp.retail_price) from product_prices sp where sp.product_id = p.id and sp.currency = :sortCurrency and sp.active) ")
						.append(s.sort() == ProductSort.PRICE_ASC ? "asc" : "desc").append(" nulls last, p.id");
				params.put("sortCurrency", s.currency().name());
			}
		}
		var query = em.createNativeQuery(select.toString());
		var count = em.createNativeQuery("select count(*)" + where);
		params.forEach((k, v) -> {
			// :exact and :sortCurrency exist only in the page query
			if (!k.equals("exact") && !k.equals("sortCurrency")) {
				count.setParameter(k, v);
			}
			query.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		timeout(query);
		timeout(count);
		var ids = new ArrayList<UUID>();
		for (var row : query.getResultList()) {
			ids.add((UUID) row);
		}
		return new PageResult<>(ids, page.page(), page.size(), ((Number) count.getSingleResult()).longValue());
	}

	private static void timeout(Query q) {
		q.setHint("jakarta.persistence.query.timeout", TIMEOUT_MS);
	}

	/** Test/ops helper: the PostgreSQL plan of a text search (used by the index-usage test and docs/search.md measurements). */
	List<String> explain(String tsQuery, boolean analyze) {
		var rows = em.createNativeQuery((analyze ? "explain (analyze, buffers) " : "explain ")
				+ "select p.id from products p where p.status = 'ACTIVE' and p.search_vector @@ to_tsquery('simple', cast(:tsq as text)) "
				+ "order by ts_rank_cd(p.search_vector, to_tsquery('simple', cast(:tsq as text)), 32) desc limit 24")
				.setParameter("tsq", tsQuery).getResultList();
		var out = new ArrayList<String>();
		rows.forEach(r -> out.add(String.valueOf(r)));
		return out;
	}
}
