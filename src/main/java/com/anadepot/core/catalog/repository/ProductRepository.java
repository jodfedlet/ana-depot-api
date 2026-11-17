package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductSearch;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: how core loads and stores products. Implemented in infra with Spring Data JPA. */
public interface ProductRepository {

	Product save(Product product);

	Optional<Product> findById(UUID id);

	boolean skuInUse(String sku, UUID exceptProductId);

	PageResult<Product> findPage(PageRequest request, ProductStatus statusOrNull);

	Optional<Product> findBySlug(String slug);

	List<UUID> idsByCategory(UUID categoryId);

	/** Products of one category in every status, newest first. */
	PageResult<Product> findPageByCategory(UUID categoryId, PageRequest request);

	/** Public catalog search: ONLY active products in active categories; see {@link ProductSearch}. */
	PageResult<Product> searchPublic(ProductSearch search, PageRequest request);
}
