package com.anadepot.core.inventory.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.util.UUID;

/**
 * THE stock position of a product (or of one variant): one record per product/variant, shared by every currency.
 * A reservation in HTG and a reservation in USD consume the same {@code onHand}.
 *
 * <pre>available = max(0, onHand - reserved)</pre>
 * {@code reserved} is the quantity held by OPEN reservations (pending, confirmed, ready). Physical loss after a
 * reservation can push {@code reserved} above {@code onHand}; available is then 0 (as in the storefront) and no new
 * reservation is accepted. Immutable: every operation returns the new position.
 *
 * <p>An untracked item (made-to-order / always offered) has no quantity limit and ignores reserve/release.
 */
public record InventoryItem(UUID id, UUID productId, UUID variantId, boolean tracked, Integer lowStockThreshold,
		int onHand, int reserved) {

	public InventoryItem {
		if (onHand < 0 || reserved < 0) {
			throw new DomainRuleViolation("stock_negative", "Stock quantities cannot be negative");
		}
		if (lowStockThreshold != null && lowStockThreshold < 0) {
			throw new DomainRuleViolation("stock_invalid", "Low-stock threshold cannot be negative");
		}
	}

	public int available() {
		return Math.max(0, onHand - reserved);
	}

	public boolean canReserve(int quantity) {
		return !tracked || quantity <= available();
	}

	public InventoryItem reserve(int quantity) {
		requirePositive(quantity);
		if (!tracked) {
			return this;
		}
		if (quantity > available()) {
			throw new DomainRuleViolation("insufficient_stock", "Only " + available() + " available, " + quantity + " requested");
		}
		return new InventoryItem(id, productId, variantId, tracked, lowStockThreshold, onHand, reserved + quantity);
	}

	public InventoryItem release(int quantity) {
		requirePositive(quantity);
		if (!tracked) {
			return this;
		}
		if (quantity > reserved) {
			throw new DomainRuleViolation("inventory_inconsistent", "Releasing " + quantity + " but only " + reserved + " reserved");
		}
		return new InventoryItem(id, productId, variantId, tracked, lowStockThreshold, onHand, reserved - quantity);
	}

	/** Reservation collected: the goods leave the building (onHand falls) and stop being reserved. */
	public InventoryItem handOver(int quantity) {
		requirePositive(quantity);
		if (!tracked) {
			return this;
		}
		if (quantity > reserved) {
			throw new DomainRuleViolation("inventory_inconsistent", "Handing over " + quantity + " but only " + reserved + " reserved");
		}
		return new InventoryItem(id, productId, variantId, tracked, lowStockThreshold, Math.max(0, onHand - quantity), reserved - quantity);
	}

	/** Manual adjustment. {@code quantity} is the amount to add/reduce, or the counted total for RECOUNT. */
	public InventoryItem adjust(MovementType type, int quantity) {
		if (!tracked) {
			throw new DomainRuleViolation("inventory_not_tracked", "Stock is not tracked for this product");
		}
		int next = switch (type) {
			case ADD -> {
				requirePositive(quantity);
				yield onHand + quantity;
			}
			case REDUCE -> {
				requirePositive(quantity);
				yield onHand - quantity;
			}
			case RECOUNT -> {
				if (quantity < 0) {
					throw new DomainRuleViolation("stock_invalid", "Counted quantity cannot be negative");
				}
				yield quantity;
			}
			default -> throw new DomainRuleViolation("stock_invalid", type + " is not a manual adjustment");
		};
		if (next < 0) {
			throw new DomainRuleViolation("stock_negative", "Stock cannot go below zero");
		}
		return new InventoryItem(id, productId, variantId, tracked, lowStockThreshold, next, reserved);
	}

	public StockState state() {
		if (!tracked) {
			return StockState.UNTRACKED;
		}
		if (available() == 0) {
			return StockState.OUT;
		}
		return lowStockThreshold != null && available() <= lowStockThreshold ? StockState.LOW : StockState.OK;
	}

	private static void requirePositive(int quantity) {
		if (quantity < 1) {
			throw new DomainRuleViolation("stock_invalid", "Quantity must be at least 1");
		}
	}

	public enum StockState { OK, LOW, OUT, UNTRACKED }
}
