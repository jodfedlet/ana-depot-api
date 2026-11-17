package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.UUID;

/**
 * A staff product listing query (every status). All filters are optional and combine with AND. {@code text} is the same sanitised full-text query as the
 * public search, plus an exact / prefix match on the SKU, variant SKU and slug (staff look products up by reference). {@code currency} means "has a
 * price in that currency"; prices of different currencies are never compared.
 */
public record AdminProductQuery(SearchQuery text, ProductStatus status, UUID categoryId, String brand, SupportedCurrency currency, StockFilter stock, Sort sort) {

	/**
	 * Stock position of the product (a product with variants has several positions): IN_STOCK = some position is untracked or has available units;
	 * OUT_OF_STOCK = it has stock records and none has available units; LOW_STOCK = a tracked position is at or below its threshold;
	 * NO_STOCK_RECORD = no stock record yet (cannot be reserved until one is registered).
	 */
	public enum StockFilter {
		IN_STOCK, OUT_OF_STOCK, LOW_STOCK, NO_STOCK_RECORD
	}

	public enum Sort {
		RELEVANCE, NEWEST, UPDATED, NAME, SKU
	}

	public AdminProductQuery {
		text = text == null ? SearchQuery.empty() : text;
		if (brand != null && (brand.isBlank() || brand.length() > 100 || brand.chars().anyMatch(Character::isISOControl))) {
			throw new DomainRuleViolation("filter_invalid", "The brand filter is not valid");
		}
		brand = brand == null ? null : brand.strip();
		sort = sort == null ? (text.isEmpty() ? Sort.NEWEST : Sort.RELEVANCE) : (sort == Sort.RELEVANCE && text.isEmpty() ? Sort.NEWEST : sort);
	}
}
