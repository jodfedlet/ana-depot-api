package com.anadepot.core.shared;

/** A referenced aggregate does not exist. */
public class NotFoundException extends DomainRuleViolation {

	public NotFoundException(String what, Object id) {
		super("not_found", what + " not found: " + id);
	}
}
