package com.anadepot.core.inventory.model;

import java.util.Collection;

/**
 * What a customer may learn about stock: an availability state and, for tracked stock, the quantity that can still be reserved
 * ({@code available = onHand - reserved}). On-hand and reserved are never exposed. {@code LOW_STOCK} exists only when the seller set a
 * threshold: no low-stock policy is invented.
 */
public record StockView(Availability availability, Integer availableQuantity) {

	public enum Availability {
		IN_STOCK, LOW_STOCK, OUT_OF_STOCK, UNTRACKED_AVAILABLE
	}

	/** A product/variant without a stock position cannot be reserved, so it is out of stock. */
	public static StockView of(InventoryItem item) {
		if (item == null) {
			return new StockView(Availability.OUT_OF_STOCK, 0);
		}
		return switch (item.state()) {
			case OK -> new StockView(Availability.IN_STOCK, item.available());
			case LOW -> new StockView(Availability.LOW_STOCK, item.available());
			case OUT -> new StockView(Availability.OUT_OF_STOCK, 0);
			case UNTRACKED -> new StockView(Availability.UNTRACKED_AVAILABLE, null);
		};
	}

	/** Summary of several variants: the best state wins; the quantity is the sum when every available variant is tracked, else null. */
	public static StockView combine(Collection<StockView> views) {
		var available = views.stream().filter(v -> v.availability() != Availability.OUT_OF_STOCK).toList();
		if (available.isEmpty()) {
			return new StockView(Availability.OUT_OF_STOCK, 0);
		}
		boolean anyUntracked = available.stream().anyMatch(v -> v.availableQuantity() == null);
		Integer total = anyUntracked ? null : available.stream().mapToInt(StockView::availableQuantity).sum();
		Availability state;
		if (available.stream().anyMatch(v -> v.availability() == Availability.IN_STOCK)) {
			state = Availability.IN_STOCK;
		} else if (anyUntracked) {
			state = Availability.UNTRACKED_AVAILABLE;
		} else {
			state = Availability.LOW_STOCK;
		}
		return new StockView(state, total);
	}
}
