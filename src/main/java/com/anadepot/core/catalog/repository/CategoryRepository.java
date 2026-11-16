package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.Category;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

	Category save(Category category);

	Optional<Category> findById(UUID id);

	/** Active categories in display order. */
	java.util.List<Category> findActive();

	Optional<Category> findActiveBySlug(String slug);

	java.util.Map<UUID, Category> findAllById(java.util.Collection<UUID> ids);

	/** Number of ACTIVE products per category id (only categories that have some). */
	java.util.Map<UUID, Long> activeProductCounts();
}
