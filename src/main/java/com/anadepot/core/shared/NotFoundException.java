package com.anadepot.core.shared;

/** A referenced aggregate does not exist. */
public class NotFoundException extends DomainRuleViolation {

	public NotFoundException(String what, Object id) {
		super("not_found", what + " not found: " + id);
	}

	private NotFoundException(String code, String message, boolean fixed) {
		super(code, message);
	}

	/** A fixed code and message that reveal nothing about WHY nothing was found (used where existence must not be learnable). */
	public static NotFoundException withCode(String code, String message) {
		return new NotFoundException(code, message, true);
	}
}
