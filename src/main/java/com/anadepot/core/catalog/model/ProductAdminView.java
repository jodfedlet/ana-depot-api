package com.anadepot.core.catalog.model;

import com.anadepot.core.inventory.model.InventoryItem;
import java.util.List;

/** A product as staff see it: the aggregate, its optimistic-concurrency version and every stock position (on hand / reserved / available are staff-only figures). */
public record ProductAdminView(Product product, long version, List<InventoryItem> stock) {

	public int onHand() {
		return stock.stream().filter(InventoryItem::tracked).mapToInt(InventoryItem::onHand).sum();
	}

	public int reserved() {
		return stock.stream().filter(InventoryItem::tracked).mapToInt(InventoryItem::reserved).sum();
	}

	public int available() {
		return stock.stream().filter(InventoryItem::tracked).mapToInt(InventoryItem::available).sum();
	}

	public boolean anyUntracked() {
		return stock.stream().anyMatch(i -> !i.tracked());
	}
}
