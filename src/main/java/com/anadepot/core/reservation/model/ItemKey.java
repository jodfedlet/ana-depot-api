package com.anadepot.core.reservation.model;

import java.util.Comparator;
import java.util.UUID;

/** Identifies one stock position: a product, or one variant of it. Ordered so locks are always taken in the same order. */
public record ItemKey(UUID productId, UUID variantId) implements Comparable<ItemKey> {

	private static final Comparator<ItemKey> ORDER = Comparator
			.comparing(ItemKey::productId)
			.thenComparing(k -> k.variantId() == null ? new UUID(0, 0) : k.variantId());

	@Override
	public int compareTo(ItemKey other) {
		return ORDER.compare(this, other);
	}
}
