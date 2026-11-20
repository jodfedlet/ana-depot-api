package com.anadepot.core.payment.model;

import java.math.BigDecimal;

/**
 * A provider notification AFTER its signature was verified and its payload reduced to what the business needs (no provider classes in core).
 * {@code kind} is the provider-neutral meaning; {@code amount}/{@code currency} are what the provider says was charged (checked against the attempt).
 */
public record ProviderEvent(String eventId, Kind kind, String sessionId, String paymentIntentId, String attemptId, BigDecimal amount, String currency, boolean paid, String failureCode) {

	public enum Kind {
		/** Checkout finished; {@code paid} says whether money is already captured (a delayed method may still be processing). */
		SESSION_COMPLETED,
		SESSION_ASYNC_SUCCEEDED,
		SESSION_ASYNC_FAILED,
		SESSION_EXPIRED,
		PAYMENT_FAILED,
		/** Informational for this integration (the Checkout events carry the outcome). */
		PAYMENT_SUCCEEDED,
		/** The owner refunded (part of) a payment in the provider's dashboard: {@code amount} is the CUMULATIVE refunded amount of that payment. Observed only (D1). */
		CHARGE_REFUNDED
	}
}
