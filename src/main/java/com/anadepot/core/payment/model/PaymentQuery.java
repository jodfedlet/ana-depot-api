package com.anadepot.core.payment.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * A staff payment listing. Filters combine with AND. {@code currency} narrows to payments in that currency: amounts of different currencies are never
 * added or compared. {@code receivedFrom} inclusive, {@code receivedTo} exclusive. {@code reversed}: true = only reversed payments, false = only live ones.
 */
public record PaymentQuery(UUID reservationId, SupportedCurrency currency, PaymentMethod method, Instant receivedFrom, Instant receivedTo, Boolean reversed, UUID recordedBy) {

	public PaymentQuery {
		if (receivedFrom != null && receivedTo != null && !receivedFrom.isBefore(receivedTo)) {
			throw new DomainRuleViolation("date_range_invalid", "The start of the period must be before its end");
		}
	}
}
