package com.anadepot.core.shared.money;

import com.anadepot.core.shared.DomainRuleViolation;

/** Two amounts of different currencies were combined. There is deliberately no conversion to fall back on. */
public class CurrencyMismatchException extends DomainRuleViolation {

	public CurrencyMismatchException(SupportedCurrency left, SupportedCurrency right) {
		super("currency_mismatch", "Cannot combine " + left + " and " + right + ": amounts in different currencies are never added, compared or converted");
	}
}
