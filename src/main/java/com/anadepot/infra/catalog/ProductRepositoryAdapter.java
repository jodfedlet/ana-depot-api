package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductSearch;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class ProductRepositoryAdapter implements ProductRepository {

	private final ProductJpaRepository products;
	private final ProductSearchNative search;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager em;

	ProductRepositoryAdapter(ProductJpaRepository products, ProductSearchNative search) {
		this.products = products;
		this.search = search;
	}

	@Override
	public Optional<Meta> meta(UUID id) {
		return products.findById(id).map(e -> new Meta(e.version == null ? 0 : e.version, e.createdAt, e.updatedAt));
	}

	@Override
	public boolean slugInUse(String slug, UUID exceptProductId) {
		return exceptProductId == null ? products.existsBySlug(slug) : products.existsBySlugAndIdNot(slug, exceptProductId);
	}

	@Override
	public boolean skuTaken(String sku, UUID exceptProductId) {
		var except = exceptProductId == null ? new UUID(0, 0) : exceptProductId;
		long products = em.createQuery("select count(p) from ProductEntity p where p.sku = :sku and p.id <> :except", Long.class).setParameter("sku", sku).setParameter("except", except).getSingleResult();
		long variants = em.createQuery("select count(v) from ProductVariantEntity v where v.sku = :sku and v.product.id <> :except", Long.class).setParameter("sku", sku).setParameter("except", except).getSingleResult();
		return products + variants > 0;
	}

	@Override
	public java.util.Set<UUID> variantIdsInUse(UUID productId) {
		var ids = new java.util.HashSet<UUID>(em.createQuery("select i.variantId from InventoryItemEntity i where i.productId = :p and i.variantId is not null", UUID.class).setParameter("p", productId).getResultList());
		ids.addAll(em.createQuery("select distinct l.variantId from ReservationLineEntity l where l.productId = :p and l.variantId is not null", UUID.class).setParameter("p", productId).getResultList());
		return ids;
	}

	@Override
	public boolean hasReservationLines(UUID productId) {
		return em.createQuery("select count(l) from ReservationLineEntity l where l.productId = :p", Long.class).setParameter("p", productId).getSingleResult() > 0;
	}

	@Override
	public boolean hasInventory(UUID productId) {
		return em.createQuery("select count(i) from InventoryItemEntity i where i.productId = :p", Long.class).setParameter("p", productId).getSingleResult() > 0;
	}

	@Override
	public void delete(UUID id) {
		products.findById(id).ifPresent(e -> {
			products.delete(e);
			products.flush();
		});
	}

	@Override
	public PageResult<Product> searchAdmin(com.anadepot.core.catalog.model.AdminProductQuery query, PageRequest request) {
		var hits = search.searchAdmin(query, request);
		var loaded = new java.util.HashMap<UUID, Product>();
		products.findAllById(hits.items()).forEach(e -> loaded.put(e.id, CatalogMapper.toDomain(e)));
		return new PageResult<>(hits.items().stream().map(loaded::get).filter(java.util.Objects::nonNull).toList(), hits.page(), hits.size(), hits.totalItems());
	}

	@Override
	public Product save(Product product) {
		var entity = products.findById(product.id()).orElseGet(() -> {
			var n = new ProductEntity();
			n.id = product.id();
			n.createdAt = product.createdAt();
			return n;
		});
		CatalogMapper.apply(product, entity);
		return CatalogMapper.toDomain(products.saveAndFlush(entity));
	}

	@Override
	public Optional<Product> findById(UUID id) {
		return products.findById(id).map(CatalogMapper::toDomain);
	}

	@Override
	public boolean skuInUse(String sku, UUID exceptProductId) {
		return exceptProductId == null ? products.existsBySku(sku) : products.existsBySkuAndIdNot(sku, exceptProductId);
	}

	@Override
	public PageResult<Product> findPage(PageRequest request, ProductStatus statusOrNull) {
		var pageable = org.springframework.data.domain.PageRequest.of(request.page(), request.size(), Sort.by("createdAt").descending().and(Sort.by("id")));
		var page = statusOrNull == null ? products.findAll(pageable) : products.findByStatus(statusOrNull, pageable);
		return new PageResult<>(page.getContent().stream().map(CatalogMapper::toDomain).toList(), request.page(), request.size(), page.getTotalElements());
	}

	@Override
	public PageResult<Product> findPageByCategory(UUID categoryId, PageRequest request) {
		var pageable = org.springframework.data.domain.PageRequest.of(request.page(), request.size(), Sort.by("createdAt").descending().and(Sort.by("id")));
		var page = products.findByCategoryId(categoryId, pageable);
		return new PageResult<>(page.getContent().stream().map(CatalogMapper::toDomain).toList(), request.page(), request.size(), page.getTotalElements());
	}

	@Override
	public Optional<Product> findBySlug(String slug) {
		return products.findBySlug(slug).map(CatalogMapper::toDomain);
	}

	@Override
	public List<UUID> idsByCategory(UUID categoryId) {
		return products.idsByCategory(categoryId);
	}

	@Override
	public PageResult<Product> searchPublic(ProductSearch query, PageRequest request) {
		var hits = search.search(query, request);
		var loaded = new java.util.HashMap<UUID, Product>();
		products.findAllById(hits.items()).forEach(e -> loaded.put(e.id, CatalogMapper.toDomain(e)));   // one query; collections batch-fetched
		return new PageResult<>(hits.items().stream().map(loaded::get).filter(java.util.Objects::nonNull).toList(), hits.page(), hits.size(), hits.totalItems());
	}
}
