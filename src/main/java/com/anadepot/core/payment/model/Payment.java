package com.anadepot.core.payment.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Money received for a reservation, in the currency it was received in. IMMUTABLE once recorded: a mistake is fixed
 * with a reversal / adjustment {@link LedgerEntry}, never by editing the payment.
 */
public record Payment(UUID id, UUID reservationId, Money amount, PaymentMethod method, Instant receivedAt,
		Instant recordedAt, UUID recordedBy, String reference, String note) {

	public Payment {
		if (!amount.isPositive()) {
			throw new DomainRuleViolation("amount_invalid", "A payment must be greater than zero");
		}
	}
}
