package com.anadepot.core.shared;

/**
 * A business rule was broken. {@code code} is a stable, machine-readable identifier (used by the API error contract
 * and, later, by the frontend to pick a translated message); the message is for developers and logs.
 */
public class DomainRuleViolation extends RuntimeException {

	private final String code;

	public DomainRuleViolation(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
