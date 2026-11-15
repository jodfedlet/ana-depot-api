package com.anadepot.core.inventory.service;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import java.util.UUID;

public interface InventoryService {

	/** Creates the stock position of a product/variant with an INITIAL movement. */
	InventoryItem register(UUID productId, UUID variantId, boolean tracked, int initialOnHand, Integer lowStockThreshold, UUID recordedBy);

	/** Add / reduce / recount, with a reason and an audit movement. Never touches reserved quantity. */
	StockMovement adjust(UUID productId, UUID variantId, MovementType type, int quantity, StockReason reason, String note, UUID recordedBy);

	InventoryItem get(UUID productId, UUID variantId);
}
