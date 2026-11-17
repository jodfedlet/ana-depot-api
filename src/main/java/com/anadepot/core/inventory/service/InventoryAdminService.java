package com.anadepot.core.inventory.service;

import com.anadepot.core.inventory.model.InventoryPositionView;
import com.anadepot.core.inventory.model.InventoryQuery;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.UUID;

/**
 * Staff stock management. Stock is shared by HTG and USD. Quantities change only through audited movements (never edited in place); {@code reserved} is
 * owned by reservations and is never touched here.
 */
public interface InventoryAdminService {

	PageResult<InventoryPositionView> list(InventoryQuery query, PageRequest page);

	InventoryPositionView get(UUID inventoryItemId);

	/**
	 * Creates the stock position of a product (or of one variant: a product with variants is stocked per variant) with an opening balance. Audited.
	 * @throws com.anadepot.core.shared.DomainRuleViolation {@code inventory_exists} when it already has one
	 */
	InventoryPositionView register(UUID productId, UUID variantId, boolean tracked, int initialOnHand, Integer lowStockThreshold, Actor actor);

	/** Add / reduce / recount with a mandatory reason. The idempotency key is REQUIRED: a retry returns the movement of the first attempt. */
	StockMovement adjust(UUID inventoryItemId, MovementType type, int quantity, StockReason reason, String note, Actor actor, String idempotencyKey);

	/** Sets or clears (null) the low-stock threshold. {@code expectedVersion} is the version the caller read. */
	InventoryPositionView updateThreshold(UUID inventoryItemId, long expectedVersion, Integer lowStockThreshold, Actor actor);

	/** The movements of one position, newest first. */
	PageResult<StockMovement> movements(UUID inventoryItemId, PageRequest page);
}
