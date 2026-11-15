package com.anadepot.core.inventory.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit record of a physical stock change. {@code delta} is signed; {@code balanceAfter} is the
 * on-hand quantity afterwards. {@code reservationId} is set for SALE movements; {@code recordedBy} is the
 * identity-provider user id (null when the system did it).
 */
public record StockMovement(UUID id, UUID inventoryItemId, MovementType type, int delta, int balanceAfter,
		StockReason reason, UUID reservationId, String note, Instant at, UUID recordedBy) {
}
