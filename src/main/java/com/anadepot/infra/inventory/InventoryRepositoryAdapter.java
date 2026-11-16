package com.anadepot.infra.inventory;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.repository.InventoryRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class InventoryRepositoryAdapter implements InventoryRepository {

	private final InventoryItemJpaRepository items;
	private final InventoryMovementJpaRepository movements;
	private final Clock clock;

	InventoryRepositoryAdapter(InventoryItemJpaRepository items, InventoryMovementJpaRepository movements, Clock clock) {
		this.items = items;
		this.movements = movements;
		this.clock = clock;
	}

	@Override
	public InventoryItem save(InventoryItem item) {
		var now = clock.instant();
		var e = items.findById(item.id()).orElseGet(() -> {
			var n = new InventoryItemEntity();
			n.id = item.id();
			n.createdAt = now;
			return n;
		});
		e.productId = item.productId();
		e.variantId = item.variantId();
		e.tracked = item.tracked();
		e.lowStockThreshold = item.lowStockThreshold();
		e.onHand = item.onHand();
		e.reserved = item.reserved();
		e.updatedAt = now;
		return toDomain(items.saveAndFlush(e));
	}

	@Override
	public Optional<InventoryItem> find(UUID productId, UUID variantId) {
		return (variantId == null ? items.findForProduct(productId) : items.findForVariant(variantId)).map(InventoryRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<InventoryItem> findForUpdate(UUID productId, UUID variantId) {
		return (variantId == null ? items.lockForProduct(productId) : items.lockForVariant(variantId)).map(InventoryRepositoryAdapter::toDomain);
	}

	@Override
	public java.util.Map<UUID, List<InventoryItem>> findAllForProducts(java.util.Collection<UUID> productIds) {
		var result = new java.util.HashMap<UUID, List<InventoryItem>>();
		if (productIds.isEmpty()) {
			return result;
		}
		items.findAllForProducts(productIds).forEach(e -> result.computeIfAbsent(e.productId, k -> new java.util.ArrayList<>()).add(toDomain(e)));
		return result;
	}

	@Override
	public StockMovement saveMovement(StockMovement m) {
		var e = new InventoryMovementEntity();
		e.id = m.id();
		e.inventoryItemId = m.inventoryItemId();
		e.movementType = m.type();
		e.delta = m.delta();
		e.balanceAfter = m.balanceAfter();
		e.reason = m.reason();
		e.reservationId = m.reservationId();
		e.note = m.note();
		e.createdAt = m.at();
		e.recordedBy = m.recordedBy();
		movements.saveAndFlush(e);
		return m;
	}

	@Override
	public List<StockMovement> movements(UUID inventoryItemId) {
		return movements.findByInventoryItemIdOrderByCreatedAtAscIdAsc(inventoryItemId).stream()
				.map(e -> new StockMovement(e.id, e.inventoryItemId, e.movementType, e.delta, e.balanceAfter, e.reason, e.reservationId, e.note, e.createdAt, e.recordedBy)).toList();
	}

	private static InventoryItem toDomain(InventoryItemEntity e) {
		return new InventoryItem(e.id, e.productId, e.variantId, e.tracked, e.lowStockThreshold, e.onHand, e.reserved);
	}
}
