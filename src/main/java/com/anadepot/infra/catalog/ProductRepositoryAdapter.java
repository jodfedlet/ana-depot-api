package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
class ProductRepositoryAdapter implements ProductRepository {

	private final ProductJpaRepository products;

	ProductRepositoryAdapter(ProductJpaRepository products) {
		this.products = products;
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
}
