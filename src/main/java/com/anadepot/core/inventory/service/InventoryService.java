package com.anadepot.core.inventory.service;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import java.util.List;
import java.util.UUID;

public interface InventoryService {

	/** Creates the stock position of a product/variant with an INITIAL movement. */
	InventoryItem register(UUID productId, UUID variantId, boolean tracked, int initialOnHand, Integer lowStockThreshold, UUID recordedBy);

	/**
	 * Add / reduce / recount, with a mandatory reason, an audit movement and an audit-trail entry. Never touches reserved
	 * quantity. The idempotency key is REQUIRED: a retried adjustment must not be applied twice.
	 */
	StockMovement adjust(UUID productId, UUID variantId, MovementType type, int quantity, StockReason reason, String note, UUID recordedBy, String idempotencyKey);

	InventoryItem get(UUID productId, UUID variantId);

	/** What customers may see about stock for many products at once (no on-hand/reserved numbers). Key: product id; value: that product's stock positions. */
	java.util.Map<UUID, List<InventoryItem>> positionsOf(java.util.Collection<UUID> productIds);
}
