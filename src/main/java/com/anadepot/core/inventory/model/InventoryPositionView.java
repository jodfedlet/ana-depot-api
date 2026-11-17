package com.anadepot.core.inventory.model;

import com.anadepot.core.catalog.model.Product;
import java.time.Instant;

/** A stock position as staff see it: the figures, the product (and variant) it belongs to, and the optimistic-concurrency version. */
public record InventoryPositionView(InventoryItem item, long version, Instant updatedAt, Product product) {

	public String variantSku() {
		return item.variantId() == null ? null : product.variants().stream().filter(v -> v.id().equals(item.variantId())).map(v -> v.sku()).findFirst().orElse(null);
	}
}
