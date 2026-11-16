package com.anadepot.core.inventory.serviceImpl;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.UUID;

public class InventoryServiceImpl implements InventoryService {

	private final InventoryRepository inventory;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final AuditRecorder audit;
	private final DomainEventRecorder events;
	private final IdempotencyService idempotency;

	public InventoryServiceImpl(InventoryRepository inventory, TransactionBoundary tx, Clock clock, AuditRecorder audit, DomainEventRecorder events, IdempotencyService idempotency) {
		this.audit = audit;
		this.events = events;
		this.idempotency = idempotency;
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
	public StockMovement adjust(UUID productId, UUID variantId, MovementType type, int quantity, StockReason reason, String note, UUID recordedBy, String key) {
		if (key == null || key.isBlank()) {
			throw new DomainRuleViolation("idempotency_key_required", "An idempotency key is required for stock adjustments");
		}
		if (reason == null) {
			throw new DomainRuleViolation("reason_required", "A stock adjustment needs a reason");
		}
		var fingerprint = Fingerprint.of(productId, variantId, type, quantity, reason, note);
		var actor = recordedBy == null ? Actor.system() : Actor.staff(recordedBy);
		return idempotency.run(key, recordedBy == null ? "system" : "staff:" + recordedBy, "inventory.adjust", fingerprint, () -> tx.inTransaction(() -> {
			var item = inventory.findForUpdate(productId, variantId).orElseThrow(() -> new NotFoundException("Inventory item", productId));
			var next = item.adjust(type, quantity);
			inventory.save(next);
			var movement = inventory.saveMovement(new StockMovement(UUID.randomUUID(), item.id(), type, next.onHand() - item.onHand(), next.onHand(), reason,
					null, note == null || note.isBlank() ? null : note.strip(), clock.instant(), recordedBy));
			audit.record(AuditEvent.of("INVENTORY_ADJUSTED", "inventory_item", item.id(), actor,
					java.util.Map.of("onHand", item.onHand()), java.util.Map.of("onHand", next.onHand(), "type", type.name()), reason.name()));
			events.record(new DomainEvent("inventory.adjusted", "inventory_item", item.id(), inventory.movements(item.id()).size(),
					java.util.Map.of("productId", productId.toString(), "onHand", next.onHand(), "reserved", next.reserved(), "delta", movement.delta())));
			return movement.id();
		}), id -> inventory.movements(inventoryItemIdOf(productId, variantId)).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow());
	}

	private UUID inventoryItemIdOf(UUID productId, UUID variantId) {
		return inventory.find(productId, variantId).orElseThrow(() -> new NotFoundException("Inventory item", productId)).id();
	}

	@Override
	public InventoryItem get(UUID productId, UUID variantId) {
		return inventory.find(productId, variantId).orElseThrow(() -> new NotFoundException("Inventory item", productId));
	}

	@Override
	public java.util.Map<UUID, java.util.List<InventoryItem>> positionsOf(java.util.Collection<UUID> productIds) {
		return inventory.findAllForProducts(productIds);
	}
}
