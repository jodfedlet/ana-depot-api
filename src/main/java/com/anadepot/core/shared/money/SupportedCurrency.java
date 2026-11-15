package com.anadepot.core.shared.money;

import com.anadepot.core.shared.DomainRuleViolation;

/**
 * Currencies Ana Dépôt sells in (confirmed business decision: HTG and USD, both from launch).
 * The enum name IS the ISO 4217 code. A currency is never inferred from a customer's country or location,
 * and there is no exchange rate anywhere in the system. Adding a currency = a new constant + a row in the
 * {@code currencies} table (see docs/database.md).
 */
public enum SupportedCurrency {

	HTG(2),
	USD(2);

	private final int fractionDigits;

	SupportedCurrency(int fractionDigits) {
		this.fractionDigits = fractionDigits;
	}

	/** Number of decimals in a normal amount of this currency (ISO 4217 minor unit). */
	public int fractionDigits() {
		return fractionDigits;
	}

	public String isoCode() {
		return name();
	}

	public static SupportedCurrency parse(String code) {
		if (code != null) {
			for (var c : values()) {
				if (c.name().equalsIgnoreCase(code.strip())) {
					return c;
				}
			}
		}
		throw new DomainRuleViolation("unsupported_currency", "Unsupported currency: " + code);
	}
}
