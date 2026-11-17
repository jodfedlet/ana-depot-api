package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.AdminProductQuery;
import com.anadepot.core.catalog.model.ProductAdminView;
import com.anadepot.core.catalog.model.ProductDraft;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.UUID;

/**
 * Staff product management. Prices of HTG and USD are independent (never converted); stock is shared and lives in the inventory context; images have their
 * own endpoints. Every change is audited; a replace needs the version the caller read ({@code concurrent_update} otherwise).
 */
public interface ProductAdminService {

	PageResult<ProductAdminView> list(AdminProductQuery query, PageRequest page);

	ProductAdminView get(UUID id);

	/** {@code idempotencyKey} optional: a retry with the same key returns the product created the first time. */
	ProductAdminView create(ProductDraft draft, Actor actor, String idempotencyKey);

	/**
	 * Replaces the product's definition (prices, variants, attributes, texts). Images and status are kept. A variant that stock or reservations point at
	 * cannot be removed (deactivate it); an active product must stay publishable.
	 */
	ProductAdminView update(UUID id, long expectedVersion, ProductDraft draft, Actor actor);

	/** Allowed: DRAFT to ACTIVE, ACTIVE to INACTIVE, INACTIVE to ACTIVE. Publishing re-checks everything a customer-facing product needs. */
	ProductAdminView setStatus(UUID id, ProductStatus status, String reason, Actor actor);

	/** Only a DRAFT with no images, no stock record and no reservation line can be deleted; anything else is deactivated, never erased. */
	void delete(UUID id, Actor actor);
}
