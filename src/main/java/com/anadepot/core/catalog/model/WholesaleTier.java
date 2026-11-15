package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.money.Money;

/** "From {@code minQuantity} units, each costs {@code unitPrice}". Belongs to one product and one currency. */
public record WholesaleTier(int minQuantity, Money unitPrice) {
}
