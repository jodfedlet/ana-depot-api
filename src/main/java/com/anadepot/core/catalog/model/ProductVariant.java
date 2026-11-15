package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.Map;
import java.util.UUID;

/**
 * An optional variant of one product (shoe size + colour, mattress dimensions...). It has its own SKU and its own
 * stock. {@code axisValues} maps a variant-axis attribute to the chosen option. {@code retailOverrides} may give the
 * variant its own retail price per currency; a currency without an override uses the product's price.
 */
public record ProductVariant(UUID id, String sku, boolean active, int position, Map<UUID, UUID> axisValues,
		Map<SupportedCurrency, Money> retailOverrides) {

	public ProductVariant {
		if (sku == null || sku.isBlank()) {
			throw new DomainRuleViolation("variant_sku_required", "A variant needs a SKU");
		}
		axisValues = Map.copyOf(axisValues);
		retailOverrides = Map.copyOf(retailOverrides);
		retailOverrides.forEach((currency, price) -> {
			if (price.currency() != currency) {
				throw new com.anadepot.core.shared.money.CurrencyMismatchException(currency, price.currency());
			}
			if (!price.isPositive()) {
				throw new DomainRuleViolation("variant_price_invalid", "A variant price must be greater than zero");
			}
		});
	}

	public Money retailOverride(SupportedCurrency currency) {
		return retailOverrides.get(currency);
	}
}
