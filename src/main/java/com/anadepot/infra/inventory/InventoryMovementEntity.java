package com.anadepot.infra.inventory;

import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockReason;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_movements")
class InventoryMovementEntity {

	@Id
	UUID id;
	UUID inventoryItemId;
	@Enumerated(EnumType.STRING)
	MovementType movementType;
	int delta;
	int balanceAfter;
	@Enumerated(EnumType.STRING)
	StockReason reason;
	UUID reservationId;
	String note;
	Instant createdAt;
	UUID recordedBy;

	protected InventoryMovementEntity() {
	}
}
