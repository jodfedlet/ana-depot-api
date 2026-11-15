package com.anadepot.core.shared;

/** Zero-based page request. Size is capped so a client cannot ask for an unbounded list. */
public record PageRequest(int page, int size) {

	public static final int MAX_SIZE = 100;

	public PageRequest {
		if (page < 0) {
			throw new DomainRuleViolation("invalid_page", "page must be >= 0");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new DomainRuleViolation("invalid_page", "size must be between 1 and " + MAX_SIZE);
		}
	}
}
