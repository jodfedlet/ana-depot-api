package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.time.Instant;
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

	// ---- administration ----

	record Meta(long version, Instant createdAt, Instant updatedAt) {
	}

	/** Staff listing in display order: every category (active or not), optionally filtered by state and by a text in the slug or any name. */
	PageResult<Category> findPage(PageRequest request, Boolean activeOrNull, String textOrNull);

	boolean slugInUse(String slug, UUID exceptCategoryId);

	Optional<Meta> meta(UUID id);

	/** Products per category id in EVERY status (only categories that have some). */
	java.util.Map<UUID, Long> productCounts(java.util.Collection<UUID> ids);

	/** Products that hold a value for the attribute. */
	long attributeValueCount(UUID attributeId);

	/** Product values and variant axes that choose the option. */
	long optionUseCount(UUID optionId);

	/** Variants whose axis is the attribute. */
	long variantAxisUseCount(UUID attributeId);

	/** ACTIVE products of the category with NO value for the attribute (they would stop being publishable if it became required). */
	long activeProductsWithoutValue(UUID categoryId, UUID attributeId);

	/** Every category id in display order. */
	java.util.List<UUID> allIdsInOrder();

	int maxDisplayOrder();

	/** Rewrites the display order: the i-th id gets order i. */
	void applyOrder(java.util.List<UUID> orderedIds);
}
