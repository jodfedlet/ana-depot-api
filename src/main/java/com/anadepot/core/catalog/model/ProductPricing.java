package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All the price configurations of one product, at most one per currency. A product may sell in HTG only, USD only
 * or both. A missing currency is NEVER generated or converted from another one: asking for it is an error.
 */
public final class ProductPricing {

	private final Map<SupportedCurrency, ProductPrice> byCurrency = new EnumMap<>(SupportedCurrency.class);

	private ProductPricing(List<ProductPrice> prices) {
		for (var p : prices) {
			if (byCurrency.put(p.currency(), p) != null) {
				throw new DomainRuleViolation("duplicate_currency_price", "The product already has a " + p.currency() + " price");
			}
		}
	}

	public static ProductPricing of(List<ProductPrice> prices) {
		return new ProductPricing(prices);
	}

	public static ProductPricing none() {
		return new ProductPricing(List.of());
	}

	public Optional<ProductPrice> price(SupportedCurrency currency) {
		return Optional.ofNullable(byCurrency.get(currency));
	}

	/** The ACTIVE price in {@code currency}, or a rule violation when the product is not sold in it. */
	public ProductPrice requireActive(SupportedCurrency currency) {
		var p = byCurrency.get(currency);
		if (p == null || !p.active()) {
			throw new DomainRuleViolation("price_not_available", "This product has no active " + currency + " price");
		}
		return p;
	}

	public List<ProductPrice> all() {
		return List.copyOf(byCurrency.values());
	}

	public boolean hasActivePrice() {
		return byCurrency.values().stream().anyMatch(ProductPrice::active);
	}
}
