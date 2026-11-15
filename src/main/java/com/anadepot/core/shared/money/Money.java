package com.anadepot.core.shared.money;

import com.anadepot.core.shared.DomainRuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money in major units (e.g. {@code 25000.00} HTG) together with its currency. Immutable.
 *
 * <p>Backend equivalent of the frontend's {@code Money} ({@code { amount: minor units, currency }}): the frontend keeps
 * integer minor units, the backend keeps {@link BigDecimal} major units with exactly the currency's number of
 * decimals. Both HTG and USD have 2, so {@code 2500000} minor units == {@code 25000.00}.
 *
 * <p>Rules: never {@code float}/{@code double}; the amount always has exactly {@code currency.fractionDigits()}
 * decimals; arithmetic requires the same currency and otherwise throws {@link CurrencyMismatchException}; creating an
 * amount with more decimals than the currency allows is an error (no silent rounding) — rounding only happens in
 * the explicit {@link #roundedFrom} used for proportional price scaling.
 */
public record Money(BigDecimal amount, SupportedCurrency currency) implements Comparable<Money> {

	public Money {
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
		if (amount.scale() > currency.fractionDigits() && amount.stripTrailingZeros().scale() > currency.fractionDigits()) {
			throw new DomainRuleViolation("invalid_amount", "Amount " + amount.toPlainString() + " has more than "
					+ currency.fractionDigits() + " decimals for " + currency);
		}
		amount = amount.setScale(currency.fractionDigits(), RoundingMode.UNNECESSARY);
	}

	public static Money of(String amount, SupportedCurrency currency) {
		try {
			return new Money(new BigDecimal(amount), currency);
		} catch (NumberFormatException e) {
			throw new DomainRuleViolation("invalid_amount", "Not a valid amount: " + amount);
		}
	}

	public static Money of(BigDecimal amount, SupportedCurrency currency) {
		return new Money(amount, currency);
	}

	public static Money zero(SupportedCurrency currency) {
		return new Money(BigDecimal.ZERO, currency);
	}

	/** Explicit rounding (HALF_UP, like the frontend's {@code Math.round}) to the currency's decimals. */
	public static Money roundedFrom(BigDecimal exact, SupportedCurrency currency) {
		return new Money(exact.setScale(currency.fractionDigits(), RoundingMode.HALF_UP), currency);
	}

	public Money plus(Money other) {
		requireSameCurrency(other);
		return new Money(amount.add(other.amount), currency);
	}

	public Money minus(Money other) {
		requireSameCurrency(other);
		return new Money(amount.subtract(other.amount), currency);
	}

	public Money negate() {
		return new Money(amount.negate(), currency);
	}

	public Money times(int quantity) {
		return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
	}

	public Money min(Money other) {
		return compareTo(other) <= 0 ? this : other;
	}

	/** {@code max(0, this - other)}: what remains to be paid; never negative. */
	public Money minusFloorZero(Money other) {
		var result = minus(other);
		return result.isNegative() ? zero(currency) : result;
	}

	public boolean isPositive() {
		return amount.signum() > 0;
	}

	public boolean isNegative() {
		return amount.signum() < 0;
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

	public boolean isLessThan(Money other) {
		return compareTo(other) < 0;
	}

	public boolean isGreaterThan(Money other) {
		return compareTo(other) > 0;
	}

	/** Orders amounts of the SAME currency; comparing different currencies is as meaningless as adding them. */
	@Override
	public int compareTo(Money other) {
		requireSameCurrency(other);
		return amount.compareTo(other.amount);
	}

	private void requireSameCurrency(Money other) {
		if (currency != other.currency) {
			throw new CurrencyMismatchException(currency, other.currency);
		}
	}

	@Override
	public String toString() {
		return amount.toPlainString() + " " + currency;
	}
}
