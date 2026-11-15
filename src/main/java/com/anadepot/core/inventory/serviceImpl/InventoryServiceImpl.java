package com.anadepot.core.inventory.serviceImpl;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.UUID;

public class InventoryServiceImpl implements InventoryService {

	private final InventoryRepository inventory;
	private final TransactionBoundary tx;
	private final Clock clock;

	public InventoryServiceImpl(InventoryRepository inventory, TransactionBoundary tx, Clock clock) {
		this.inventory = inventory;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public InventoryItem register(UUID productId, UUID variantId, boolean tracked, int initialOnHand, Integer lowStockThreshold, UUID recordedBy) {
		return tx.inTransaction(() -> {
			if (inventory.find(productId, variantId).isPresent()) {
				throw new DomainRuleViolation("inventory_exists", "This product/variant already has a stock position");
			}
			var item = inventory.save(new InventoryItem(UUID.randomUUID(), productId, variantId, tracked, lowStockThreshold, tracked ? initialOnHand : 0, 0));
			if (tracked) {
				inventory.saveMovement(new StockMovement(UUID.randomUUID(), item.id(), MovementType.INITIAL, initialOnHand, initialOnHand, StockReason.CORRECTION, null, null, clock.instant(), recordedBy));
			}
			return item;
		});
	}

	@Override
	public StockMovement adjust(UUID productId, UUID variantId, MovementType type, int quantity, StockReason reason, String note, UUID recordedBy) {
		return tx.inTransaction(() -> {
			var item = inventory.findForUpdate(productId, variantId).orElseThrow(() -> new NotFoundException("Inventory item", productId));
			var next = item.adjust(type, quantity);
			inventory.save(next);
			return inventory.saveMovement(new StockMovement(UUID.randomUUID(), item.id(), type, next.onHand() - item.onHand(), next.onHand(), reason,
					null, note == null || note.isBlank() ? null : note.strip(), clock.instant(), recordedBy));
		});
	}

	@Override
	public InventoryItem get(UUID productId, UUID variantId) {
		return inventory.find(productId, variantId).orElseThrow(() -> new NotFoundException("Inventory item", productId));
	}
}
