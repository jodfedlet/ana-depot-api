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

	/** Every stock position of the given products (product-level and variant-level) in one query. */
	java.util.Map<UUID, List<InventoryItem>> findAllForProducts(java.util.Collection<UUID> productIds);

	Optional<InventoryItem> findById(UUID id);

	record Meta(long version, java.time.Instant createdAt, java.time.Instant updatedAt) {
	}

	Optional<Meta> meta(UUID id);

	/** Staff listing of stock positions (see {@link com.anadepot.core.inventory.model.InventoryQuery}). */
	com.anadepot.core.shared.PageResult<InventoryItem> findPage(com.anadepot.core.inventory.model.InventoryQuery query, com.anadepot.core.shared.PageRequest page);

	/** The movements of one stock position, newest first. */
	com.anadepot.core.shared.PageResult<StockMovement> movementsPage(UUID inventoryItemId, com.anadepot.core.shared.PageRequest page);

	StockMovement saveMovement(StockMovement movement);

	List<StockMovement> movements(UUID inventoryItemId);
}
