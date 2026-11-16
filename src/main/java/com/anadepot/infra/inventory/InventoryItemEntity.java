package com.anadepot.infra.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_items")
public class InventoryItemEntity {

	@Id
	UUID id;
	UUID productId;
	UUID variantId;
	boolean tracked;
	Integer lowStockThreshold;
	int onHand;
	int reserved;
	Instant createdAt;
	Instant updatedAt;
	@Version
	Long version;

	protected InventoryItemEntity() {
	}
}
