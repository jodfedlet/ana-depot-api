package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.repository.CategoryRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class CategoryRepositoryAdapter implements CategoryRepository {

	private final CategoryJpaRepository categories;
	private final Clock clock;
	private final jakarta.persistence.EntityManager em;

	CategoryRepositoryAdapter(CategoryJpaRepository categories, Clock clock, jakarta.persistence.EntityManager em) {
		this.categories = categories;
		this.clock = clock;
		this.em = em;
	}

	@Override
	public Category save(Category category) {
		var now = clock.instant();
		var entity = categories.findById(category.id()).orElseGet(() -> {
			var n = new CategoryEntity();
			n.id = category.id();
			n.createdAt = now;
			return n;
		});
		CatalogMapper.apply(category, entity, now);
		return CatalogMapper.toDomain(categories.saveAndFlush(entity));
	}

	@Override
	public Optional<Category> findById(UUID id) {
		return categories.findById(id).map(CatalogMapper::toDomain);
	}

	@Override
	public java.util.List<Category> findActive() {
		return categories.findByActiveTrueOrderByDisplayOrderAscSlugAsc().stream().map(CatalogMapper::toDomain).toList();
	}

	@Override
	public Optional<Category> findActiveBySlug(String slug) {
		return categories.findBySlugAndActiveTrue(slug).map(CatalogMapper::toDomain);
	}

	@Override
	public java.util.Map<UUID, Category> findAllById(java.util.Collection<UUID> ids) {
		var result = new java.util.HashMap<UUID, Category>();
		categories.findAllById(ids).forEach(e -> result.put(e.id, CatalogMapper.toDomain(e)));
		return result;
	}

	@Override
	public java.util.Map<UUID, Long> activeProductCounts() {
		var result = new java.util.HashMap<UUID, Long>();
		for (var row : categories.activeProductCounts()) {
			result.put((UUID) row[0], (Long) row[1]);
		}
		return result;
	}

	// ---- administration ----

	@Override
	public com.anadepot.core.shared.PageResult<Category> findPage(com.anadepot.core.shared.PageRequest request, Boolean activeOrNull, String textOrNull) {
		org.springframework.data.jpa.domain.Specification<CategoryEntity> spec = (root, query, cb) -> {
			var all = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
			if (activeOrNull != null) {
				all.add(cb.equal(root.get("active"), activeOrNull));
			}
			if (textOrNull != null) {
				var like = "%" + textOrNull.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
				var fr = cb.function("jsonb_extract_path_text", String.class, root.get("name"), cb.literal("fr"));
				var ht = cb.function("jsonb_extract_path_text", String.class, root.get("name"), cb.literal("ht"));
				all.add(cb.or(cb.like(cb.lower(root.<String>get("slug")), like, '\\'), cb.like(cb.lower(fr), like, '\\'), cb.like(cb.lower(ht), like, '\\')));
			}
			return cb.and(all.toArray(new jakarta.persistence.criteria.Predicate[0]));
		};
		var pageable = org.springframework.data.domain.PageRequest.of(request.page(), request.size(), org.springframework.data.domain.Sort.by("displayOrder").and(org.springframework.data.domain.Sort.by("slug")));
		var page = categories.findAll(spec, pageable);
		return new com.anadepot.core.shared.PageResult<>(page.getContent().stream().map(CatalogMapper::toDomain).toList(), request.page(), request.size(), page.getTotalElements());
	}

	@Override
	public boolean slugInUse(String slug, UUID exceptCategoryId) {
		return exceptCategoryId == null ? categories.existsBySlug(slug) : categories.existsBySlugAndIdNot(slug, exceptCategoryId);
	}

	@Override
	public Optional<Meta> meta(UUID id) {
		return categories.findById(id).map(e -> new Meta(e.version == null ? 0 : e.version, e.createdAt, e.updatedAt));
	}

	@Override
	public java.util.Map<UUID, Long> productCounts(java.util.Collection<UUID> ids) {
		var result = new java.util.HashMap<UUID, Long>();
		if (ids.isEmpty()) {
			return result;
		}
		for (Object[] row : em.createQuery("select p.categoryId, count(p) from ProductEntity p where p.categoryId in :ids group by p.categoryId", Object[].class).setParameter("ids", ids).getResultList()) {
			result.put((UUID) row[0], (Long) row[1]);
		}
		return result;
	}

	@Override
	public long attributeValueCount(UUID attributeId) {
		return em.createQuery("select count(v) from ProductAttributeValueEntity v where v.attributeId = :a", Long.class).setParameter("a", attributeId).getSingleResult();
	}

	@Override
	public long optionUseCount(UUID optionId) {
		long values = em.createQuery("select count(v) from ProductAttributeValueEntity v where v.optionId = :o", Long.class).setParameter("o", optionId).getSingleResult();
		long axes = em.createQuery("select count(x) from VariantAxisValueEntity x where x.optionId = :o", Long.class).setParameter("o", optionId).getSingleResult();
		return values + axes;
	}

	@Override
	public long variantAxisUseCount(UUID attributeId) {
		return em.createQuery("select count(x) from VariantAxisValueEntity x where x.attributeId = :a", Long.class).setParameter("a", attributeId).getSingleResult();
	}

	@Override
	public long activeProductsWithoutValue(UUID categoryId, UUID attributeId) {
		return em.createQuery("select count(p) from ProductEntity p where p.categoryId = :c and p.status = com.anadepot.core.catalog.model.ProductStatus.ACTIVE"
				+ " and not exists (select 1 from ProductAttributeValueEntity v where v.product = p and v.attributeId = :a)", Long.class)
				.setParameter("c", categoryId).setParameter("a", attributeId).getSingleResult();
	}

	@Override
	public java.util.List<UUID> allIdsInOrder() {
		return categories.idsInOrder();
	}

	@Override
	public int maxDisplayOrder() {
		return categories.maxDisplayOrder();
	}

	@Override
	public void applyOrder(java.util.List<UUID> orderedIds) {
		var byId = new java.util.HashMap<UUID, CategoryEntity>();
		categories.findAllById(orderedIds).forEach(e -> byId.put(e.id, e));
		var now = clock.instant();
		for (int i = 0; i < orderedIds.size(); i++) {
			var e = byId.get(orderedIds.get(i));
			if (e.displayOrder != i) {
				e.displayOrder = i;
				e.updatedAt = now;
			}
		}
		categories.flush();
	}
}
