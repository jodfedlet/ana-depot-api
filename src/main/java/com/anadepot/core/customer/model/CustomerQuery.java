package com.anadepot.core.customer.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.time.Instant;

/**
 * A staff customer listing. Filters combine with AND. {@code text} matches names, e-mail or phone digits. {@code kind}: REGISTERED (owns an account) or
 * GUEST (created from a guest reservation). Guests and accounts are never merged, so the same person may appear once as each.
 */
public record CustomerQuery(Kind kind, String text, Instant createdFrom, Instant createdTo, Sort sort) {

	public enum Kind {
		REGISTERED, GUEST
	}

	public enum Sort {
		NEWEST, NAME
	}

	public CustomerQuery {
		if (text != null && (text.length() > 100 || text.chars().anyMatch(Character::isISOControl))) {
			throw new DomainRuleViolation("filter_invalid", "The search text is not valid");
		}
		text = text == null || text.isBlank() ? null : text.strip();
		if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
			throw new DomainRuleViolation("date_range_invalid", "The start of the period must be before its end");
		}
		sort = sort == null ? Sort.NEWEST : sort;
	}
}
