package com.anadepot.infra.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.catalog.model.SearchQuery;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.infra.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves, on a representative generated catalog, that PostgreSQL answers a text search through the GIN index (EXPLAIN executed through JPA). */
class CatalogSearchPlanIntegrationTest extends AbstractIntegrationTest {

	@Autowired ProductSearchNative search;
	@Autowired InventoryRepository inventoryRepository;
	@Autowired CatalogService catalogService;
	@Autowired PlatformTransactionManager tm;
	@PersistenceContext EntityManager em;

	@Test
	void aSelectiveTextSearchUsesTheGinIndexOnAGeneratedCatalog() {
		var tx = new TransactionTemplate(tm);
		var generator = new CatalogGenerator(catalogService, inventoryRepository, search, em, tx, 7L);
		generator.products(2_500, generator.categories(8), "PLN");
		tx.executeWithoutResult(s -> em.createNativeQuery("analyze products").executeUpdate());

		// With sequential scans discouraged the planner MUST answer both selective queries through the GIN index: proof that the index supports the exact
		// predicate the application issues (the natural plan is a cost decision that depends on table statistics, so it is not asserted here; it is measured
		// at 20 000 products by CatalogSearchPerformanceTest and recorded in docs/search.md).
		tx.executeWithoutResult(s -> {
			em.createNativeQuery("set local enable_seqscan = off").executeUpdate();
			var selective = String.join("\n", search.explain(SearchQuery.of("002345").tsQuery(), false));
			System.out.println("EXPLAIN (selective: SKU number, seqscan off):\n" + selective);
			assertThat(selective).contains("Bitmap Index Scan on products_search_vector_idx").contains("search_vector @@");
			var multi = String.join("\n", search.explain(SearchQuery.of("00234 pln").tsQuery(), false));
			assertThat(multi).contains("products_search_vector_idx");
		});
		System.out.println("EXPLAIN (natural plan, non-selective ~5% of rows):\n" + String.join("\n", search.explain(SearchQuery.of("brouette").tsQuery(), false)));
	}
}
