package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * The price configuration of ONE product in ONE currency (a product has at most one per currency, and may have
 * HTG only, USD only or both). Validation follows the frontend's {@code validatePricing}:
 * <ul>
 *   <li>retail &gt; 0; sale (optional) &gt; 0 and &lt; retail;</li>
 *   <li>minimum order quantity &ge; 1;</li>
 *   <li>when wholesale is enabled: at least one tier; tier quantities integers &ge; 2 and strictly ascending;
 *       tier prices &gt; 0, strictly decreasing and below the base price (sale price if any, else retail);</li>
 *   <li>when wholesale is disabled: no tiers.</li>
 * </ul>
 * Every amount must be in {@code currency}.
 */
public record ProductPrice(SupportedCurrency currency, Money retail, Money sale, int minOrderQuantity,
		boolean wholesaleEnabled, List<WholesaleTier> tiers, boolean active) {

	public ProductPrice {
		requireCurrency(currency, retail);
		if (!retail.isPositive()) {
			throw new DomainRuleViolation("retail_price_invalid", "Retail price must be greater than zero (" + currency + ")");
		}
		if (sale != null) {
			requireCurrency(currency, sale);
			if (!sale.isPositive() || !sale.isLessThan(retail)) {
				throw new DomainRuleViolation("sale_price_invalid", "Sale price must be greater than zero and lower than the retail price (" + currency + ")");
			}
		}
		if (minOrderQuantity < 1) {
			throw new DomainRuleViolation("min_order_invalid", "Minimum order quantity must be at least 1");
		}
		tiers = tiers.stream().sorted(Comparator.comparingInt(WholesaleTier::minQuantity)).toList();
		var base = sale != null ? sale : retail;
		if (!wholesaleEnabled) {
			if (!tiers.isEmpty()) {
				throw new DomainRuleViolation("wholesale_disabled_with_tiers", "Wholesale tiers given while wholesale is disabled (" + currency + ")");
			}
		} else {
			if (tiers.isEmpty()) {
				throw new DomainRuleViolation("tiers_empty", "Wholesale is enabled but has no tiers (" + currency + ")");
			}
			WholesaleTier previous = null;
			for (var t : tiers) {
				requireCurrency(currency, t.unitPrice());
				if (t.minQuantity() < 2) {
					throw new DomainRuleViolation("tier_quantity_invalid", "A wholesale tier starts at 2 units or more");
				}
				if (!t.unitPrice().isPositive()) {
					throw new DomainRuleViolation("tier_price_invalid", "A tier price must be greater than zero");
				}
				if (!t.unitPrice().isLessThan(base)) {
					throw new DomainRuleViolation("tier_above_base", "A tier price must be lower than the base price (" + currency + ")");
				}
				if (previous != null) {
					if (t.minQuantity() == previous.minQuantity()) {
						throw new DomainRuleViolation("tier_quantity_order", "Two tiers share the quantity " + t.minQuantity());
					}
					if (!t.unitPrice().isLessThan(previous.unitPrice())) {
						throw new DomainRuleViolation("tier_price_order", "Tier prices must fall as quantities grow");
					}
				}
				previous = t;
			}
		}
	}

	private static void requireCurrency(SupportedCurrency currency, Money m) {
		if (m.currency() != currency) {
			throw new com.anadepot.core.shared.money.CurrencyMismatchException(currency, m.currency());
		}
	}

	private void requireCurrency(Money m) {
		requireCurrency(currency, m);
	}

	/** Unit price before any wholesale tier: the sale price if there is one, else retail. */
	public Money basePrice() {
		return sale != null ? sale : retail;
	}

	/**
	 * Price per unit for {@code quantity}. {@code variantRetail} is the variant's own retail price in this currency, or
	 * null. Semantics preserved from the frontend ({@code snapshotFor} + {@code unitPriceFor}):
	 * <ol>
	 *   <li>A variant with its own price ignores the product's sale price and uses its price as base.</li>
	 *   <li>The tiers are defined against the PRODUCT retail price; a variant with its own price gets the same
	 *       proportional reduction: {@code tier * variantRetail / productRetail}, rounded HALF_UP once to the
	 *       currency's decimals.</li>
	 *   <li>A tier price never exceeds the base price.</li>
	 *   <li>The highest tier whose threshold the quantity reaches applies (WHOLESALE); otherwise SALE if base is
	 *       below retail, else RETAIL.</li>
	 * </ol>
	 */
	public AppliedPrice quote(Money variantRetail, int quantity) {
		if (quantity < 1) {
			throw new DomainRuleViolation("quantity_invalid", "Quantity must be at least 1");
		}
		Money retailBase;
		Money base;
		if (variantRetail != null) {
			requireCurrency(variantRetail);
			retailBase = variantRetail;
			base = variantRetail;
		} else {
			retailBase = retail;
			base = basePrice();
		}
		WholesaleTier reached = null;
		if (wholesaleEnabled) {
			for (var t : tiers) {
				if (quantity >= t.minQuantity()) {
					reached = t;
				}
			}
		}
		if (reached != null) {
			var tierPrice = variantRetail == null ? reached.unitPrice() : scale(reached.unitPrice(), variantRetail);
			return new AppliedPrice(tierPrice.min(base), PricingKind.WHOLESALE, reached.minQuantity());
		}
		return new AppliedPrice(base, base.isLessThan(retailBase) ? PricingKind.SALE : PricingKind.RETAIL, null);
	}

	/** {@code tierPrice * variantRetail / productRetail}, rounded HALF_UP exactly once to the currency's decimals. */
	private Money scale(Money tierPrice, Money variantRetail) {
		var scaled = tierPrice.amount().multiply(variantRetail.amount()).divide(retail.amount(), currency.fractionDigits(), RoundingMode.HALF_UP);
		return Money.of(scaled, currency);
	}
}
