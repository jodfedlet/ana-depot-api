package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.CategoryAdminView;
import com.anadepot.core.catalog.model.CategoryDraft;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.UUID;

/**
 * Staff category management. Categories define the attributes of their products, so every change is checked against how the catalog uses it:
 * an attribute or option that products or variants rely on cannot be removed, an attribute that holds values cannot change type, a required
 * attribute cannot be imposed on active products that lack it, a category with active products cannot be deactivated. Every change is audited.
 */
public interface CategoryAdminService {

	PageResult<CategoryAdminView> list(PageRequest page, Boolean activeOrNull, String textOrNull);

	CategoryAdminView get(UUID id);

	/** {@code idempotencyKey} optional: a retry with the same key returns the category created the first time. */
	CategoryAdminView create(CategoryDraft draft, Actor actor, String idempotencyKey);

	/** Replaces the category. {@code expectedVersion} must equal the current version, otherwise {@code concurrent_update} (someone else changed it). */
	CategoryAdminView update(UUID id, long expectedVersion, CategoryDraft draft, Actor actor);

	/** Activates or deactivates. Deactivating a category that still has ACTIVE products is refused ({@code category_has_active_products}). */
	CategoryAdminView setActive(UUID id, boolean active, String reason, Actor actor);

	/** The new display order: EVERY category id exactly once. */
	List<CategoryAdminView> reorder(List<UUID> orderedIds, Actor actor);

	/** The products of a category in every status. */
	PageResult<Product> products(UUID categoryId, PageRequest page);

	/**
	 * Moves products to another category, all or nothing. Each product is re-validated against the target category (its attribute values and
	 * variant axes must exist there, an ACTIVE product must still be publishable), so an incompatible move fails with the usual product error.
	 * @return the number of products moved
	 */
	int moveProducts(UUID fromCategoryId, UUID toCategoryId, List<UUID> productIds, Actor actor);
}
