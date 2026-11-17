package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * A staff reservation listing. All filters are optional and combine with AND. {@code text} matches the reference (prefix), the contact's names, e-mail
 * or phone digits. {@code currency} means "has a total in that currency"; amounts of different currencies are never compared.
 * {@code createdFrom} is inclusive, {@code createdTo} exclusive.
 */
public record ReservationQuery(ReservationStatus status, PaymentStatus paymentStatus, SupportedCurrency currency, String text, UUID productId, UUID customerId,
		Instant createdFrom, Instant createdTo, Sort sort) {

	public enum Sort {
		NEWEST, OLDEST
	}

	public ReservationQuery {
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
