package com.anadepot.core.payment.service;

import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/** {@code idempotencyKey} is REQUIRED: a retried payment must never be applied twice. */
public record RecordPaymentCommand(UUID reservationId, Money amount, PaymentMethod method, Instant receivedAt,
		Actor actor, String reference, String note, String idempotencyKey) {
}
