package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {

	Page<ProductEntity> findByStatus(ProductStatus status, Pageable pageable);

	boolean existsBySku(String sku);

	boolean existsBySkuAndIdNot(String sku, UUID id);
}
