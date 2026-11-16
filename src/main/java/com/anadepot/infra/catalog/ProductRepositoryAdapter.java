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

	ProductRepositoryAdapter(ProductJpaRepository products, ProductSearchNative search) {
		this.products = products;
		this.search = search;
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
