package com.anadepot.core.shared;

import java.util.List;

/** Several input problems at once, each tied to a field and a stable code (maps to HTTP 422 with an {@code errors} array). */
public class FieldValidationException extends DomainRuleViolation {

	public record FieldIssue(String field, String code) {
	}

	private final List<FieldIssue> issues;

	public FieldValidationException(List<FieldIssue> issues) {
		super("validation_failed", "The request has " + issues.size() + " invalid field(s)");
		this.issues = List.copyOf(issues);
	}

	public List<FieldIssue> issues() {
		return issues;
	}
}
