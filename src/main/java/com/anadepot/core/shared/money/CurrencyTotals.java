package com.anadepot.core.shared.money;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * One amount per currency — what a reservation's total looks like. HTG and USD amounts are kept side by side and are
 * never merged: there is no "grand total". Immutable; iteration order follows {@link SupportedCurrency}.
 */
public final class CurrencyTotals {

	private static final CurrencyTotals EMPTY = new CurrencyTotals(new EnumMap<>(SupportedCurrency.class));

	private final Map<SupportedCurrency, Money> byCurrency;

	private CurrencyTotals(Map<SupportedCurrency, Money> byCurrency) {
		this.byCurrency = byCurrency;
	}

	public static CurrencyTotals empty() {
		return EMPTY;
	}

	public static CurrencyTotals sum(Collection<Money> amounts) {
		var totals = EMPTY;
		for (var m : amounts) {
			totals = totals.plus(m);
		}
		return totals;
	}

	public CurrencyTotals plus(Money amount) {
		var next = new EnumMap<SupportedCurrency, Money>(SupportedCurrency.class);
		next.putAll(byCurrency);
		next.merge(amount.currency(), amount, Money::plus);
		return new CurrencyTotals(next);
	}

	/** The amount in {@code currency}, or zero when this collection holds none. */
	public Money get(SupportedCurrency currency) {
		return byCurrency.getOrDefault(currency, Money.zero(currency));
	}

	public boolean has(SupportedCurrency currency) {
		return byCurrency.containsKey(currency);
	}

	public Collection<Money> amounts() {
		return byCurrency.values();
	}

	public java.util.Set<SupportedCurrency> currencies() {
		return byCurrency.keySet();
	}

	public boolean isEmpty() {
		return byCurrency.isEmpty();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CurrencyTotals other && byCurrency.equals(other.byCurrency);
	}

	@Override
	public int hashCode() {
		return byCurrency.hashCode();
	}

	@Override
	public String toString() {
		return byCurrency.values().toString();
	}
}
