package com.anadepot.core.inventory.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.util.UUID;

/**
 * A staff stock listing. Filters combine with AND. {@code text} matches the product SKU, a variant SKU or the product name (plain text, case-insensitive).
 * {@code state} uses {@link InventoryItem.StockState}: OUT = no unit available, LOW = available at or below the seller's threshold (only when one is set),
 * UNTRACKED = made to order / always offered.
 */
public record InventoryQuery(UUID productId, UUID categoryId, InventoryItem.StockState state, String text, Sort sort) {

	public enum Sort {
		/** Most recently changed first. */
		UPDATED,
		/** Least available first: what needs restocking comes first. */
		AVAILABLE_ASC
	}

	public InventoryQuery {
		if (text != null && (text.length() > 100 || text.chars().anyMatch(Character::isISOControl))) {
			throw new DomainRuleViolation("filter_invalid", "The search text is not valid");
		}
		text = text == null || text.isBlank() ? null : text.strip();
		sort = sort == null ? Sort.UPDATED : sort;
	}
}
