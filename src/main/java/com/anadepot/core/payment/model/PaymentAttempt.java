package com.anadepot.core.payment.model;

import com.anadepot.core.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One online payment attempt for ONE currency of ONE reservation. It is a work record, not money: money exists only as a {@link Payment} plus its
 * ledger entry, created from a verified provider event. {@code amount} is what the backend asked the provider to charge.
 */
public record PaymentAttempt(UUID id, UUID reservationId, Money amount, AttemptStatus status, String sessionId, String paymentIntentId, String checkoutUrl,
		Instant expiresAt, UUID paymentId, boolean requiresAttention, String attentionReason, String failureCode, UUID createdByUserId, Instant createdAt, Instant updatedAt) {

	public static PaymentAttempt open(UUID id, UUID reservationId, Money amount, UUID createdBy, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, AttemptStatus.PENDING, null, null, null, null, null, false, null, null, createdBy, now, now);
	}

	public PaymentAttempt withSession(String sessionId, String checkoutUrl, Instant expiresAt, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, status, sessionId, paymentIntentId, checkoutUrl, expiresAt, paymentId, requiresAttention, attentionReason, failureCode, createdByUserId, createdAt, now);
	}

	public PaymentAttempt withStatus(AttemptStatus next, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, next, sessionId, paymentIntentId, checkoutUrl, expiresAt, paymentId, requiresAttention, attentionReason, failureCode, createdByUserId, createdAt, now);
	}

	public PaymentAttempt withPaymentIntent(String intentId, Instant now) {
		return intentId == null || intentId.equals(paymentIntentId) ? this
				: new PaymentAttempt(id, reservationId, amount, status, sessionId, intentId, checkoutUrl, expiresAt, paymentId, requiresAttention, attentionReason, failureCode, createdByUserId, createdAt, now);
	}

	public PaymentAttempt withFailure(String code, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, status, sessionId, paymentIntentId, checkoutUrl, expiresAt, paymentId, requiresAttention, attentionReason, code, createdByUserId, createdAt, now);
	}

	public PaymentAttempt succeeded(UUID paymentId, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, AttemptStatus.SUCCEEDED, sessionId, paymentIntentId, checkoutUrl, expiresAt, paymentId, requiresAttention, attentionReason, failureCode, createdByUserId, createdAt, now);
	}

	public PaymentAttempt flagged(String reason, Instant now) {
		return new PaymentAttempt(id, reservationId, amount, status, sessionId, paymentIntentId, checkoutUrl, expiresAt, paymentId, true, reason, failureCode, createdByUserId, createdAt, now);
	}

	/** The open attempt can still be paid: it has a session that has not reached its expiry. */
	public boolean payable(Instant now) {
		return status.open() && sessionId != null && checkoutUrl != null && (expiresAt == null || expiresAt.isAfter(now));
	}
}
