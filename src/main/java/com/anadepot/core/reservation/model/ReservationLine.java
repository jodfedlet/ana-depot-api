package com.anadepot.core.reservation.model;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import java.util.UUID;

/**
 * What the customer reserved, FROZEN at submission: product and variant identity, the name and SKU as they were,
 * the selling unit, the quantity, the currency, the unit price that applied, which kind of price it was and which
 * wholesale tier, and the line total. Later product or price changes never touch a line.
 */
public record ReservationLine(UUID id, UUID productId, UUID variantId, String productName, String productSku,
		String variantLabel, SellingUnit unit, int quantity, Money unitPrice, PricingKind pricingKind,
		Integer tierMinQuantity, Money lineTotal) {

	public ReservationLine {
		if (quantity < 1) {
			throw new DomainRuleViolation("quantity_invalid", "Quantity must be at least 1");
		}
		if (!unitPrice.isPositive()) {
			throw new DomainRuleViolation("price_invalid", "A reservation line needs a positive unit price");
		}
		if (lineTotal.currency() != unitPrice.currency() || lineTotal.compareTo(unitPrice.times(quantity)) != 0) {
			throw new DomainRuleViolation("line_total_mismatch", "Line total must equal unit price x quantity in the same currency");
		}
		if ((pricingKind == PricingKind.WHOLESALE) != (tierMinQuantity != null)) {
			throw new DomainRuleViolation("tier_mismatch", "A wholesale tier is recorded exactly when the price kind is WHOLESALE");
		}
	}

	public ItemKey itemKey() {
		return new ItemKey(productId, variantId);
	}
}
