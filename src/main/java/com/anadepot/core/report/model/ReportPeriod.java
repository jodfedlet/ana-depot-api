package com.anadepot.core.report.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A reporting period of whole days in the store's time zone: {@code from} and {@code to} are both INCLUSIVE dates. Internally an instant range
 * {@code [start, end)}. A period is limited to {@value #MAX_DAYS} days so a report never scans unbounded history.
 */
public record ReportPeriod(LocalDate from, LocalDate to, ZoneId zone) {

	public static final int MAX_DAYS = 366;

	public ReportPeriod {
		if (from == null || to == null || zone == null) {
			throw new DomainRuleViolation("period_invalid", "A report needs a period");
		}
		if (to.isBefore(from)) {
			throw new DomainRuleViolation("date_range_invalid", "The start of the period must not be after its end");
		}
		if (from.plusDays(MAX_DAYS).isBefore(to)) {
			throw new DomainRuleViolation("period_too_long", "A report covers at most " + MAX_DAYS + " days");
		}
	}

	public Instant start() {
		return from.atStartOfDay(zone).toInstant();
	}

	/** Exclusive end: the start of the day after {@code to}. */
	public Instant end() {
		return to.plusDays(1).atStartOfDay(zone).toInstant();
	}
}
