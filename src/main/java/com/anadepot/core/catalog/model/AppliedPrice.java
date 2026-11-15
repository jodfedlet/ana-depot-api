package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.money.Money;

/** The price that applies to one line at a given quantity — exactly what a reservation line snapshots. */
public record AppliedPrice(Money unitPrice, PricingKind kind, Integer tierMinQuantity) {

	public Money lineTotal(int quantity) {
		return unitPrice.times(quantity);
	}
}
