package com.anadepot.infra.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID> {

	List<CategoryEntity> findByActiveTrueOrderByDisplayOrderAscSlugAsc();

	Optional<CategoryEntity> findBySlugAndActiveTrue(String slug);

	@Query("select p.categoryId, count(p) from ProductEntity p where p.status = com.anadepot.core.catalog.model.ProductStatus.ACTIVE group by p.categoryId")
	List<Object[]> activeProductCounts();
}
