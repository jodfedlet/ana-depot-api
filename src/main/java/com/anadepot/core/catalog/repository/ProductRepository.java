package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.Optional;
import java.util.UUID;

/** Port: how core loads and stores products. Implemented in infra with Spring Data JPA. */
public interface ProductRepository {

	Product save(Product product);

	Optional<Product> findById(UUID id);

	boolean skuInUse(String sku, UUID exceptProductId);

	PageResult<Product> findPage(PageRequest request, ProductStatus statusOrNull);
}
