package com.anadepot.core.inventory.repository;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.StockMovement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {

	InventoryItem save(InventoryItem item);

	Optional<InventoryItem> find(UUID productId, UUID variantId);

	/**
	 * Loads the stock position holding a database row lock until the surrounding transaction ends
	 * ({@code SELECT ... FOR UPDATE}). This is what prevents two concurrent reservations from selling the same unit.
	 * Callers that lock several items MUST lock them in a stable order (see ReservationServiceImpl).
	 */
	Optional<InventoryItem> findForUpdate(UUID productId, UUID variantId);

	StockMovement saveMovement(StockMovement movement);

	List<StockMovement> movements(UUID inventoryItemId);
}
