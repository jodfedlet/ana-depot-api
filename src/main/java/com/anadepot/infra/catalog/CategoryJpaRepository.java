package com.anadepot.infra.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<CategoryEntity> {

	boolean existsBySlug(String slug);

	boolean existsBySlugAndIdNot(String slug, UUID id);

	@Query("select c.id from CategoryEntity c order by c.displayOrder, c.slug")
	List<UUID> idsInOrder();

	@Query("select coalesce(max(c.displayOrder), -1) from CategoryEntity c")
	int maxDisplayOrder();


	List<CategoryEntity> findByActiveTrueOrderByDisplayOrderAscSlugAsc();

	Optional<CategoryEntity> findBySlugAndActiveTrue(String slug);

	@Query("select p.categoryId, count(p) from ProductEntity p where p.status = com.anadepot.core.catalog.model.ProductStatus.ACTIVE group by p.categoryId")
	List<Object[]> activeProductCounts();
}
