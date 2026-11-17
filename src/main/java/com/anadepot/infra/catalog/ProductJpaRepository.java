package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {

	Optional<ProductEntity> findBySlug(String slug);

	@org.springframework.data.jpa.repository.Query("select p.id from ProductEntity p where p.categoryId = :categoryId")
	java.util.List<UUID> idsByCategory(UUID categoryId);

	Page<ProductEntity> findByCategoryId(UUID categoryId, Pageable pageable);

	Page<ProductEntity> findByStatus(ProductStatus status, Pageable pageable);

	boolean existsBySku(String sku);

	boolean existsBySkuAndIdNot(String sku, UUID id);
}
