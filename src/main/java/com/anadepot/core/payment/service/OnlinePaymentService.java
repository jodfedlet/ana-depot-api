package com.anadepot.core.payment.service;

import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.payment.model.ProviderEvent;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Online payment through a hosted provider checkout. The payment LEDGER stays authoritative: this service only opens attempts and, when the provider
 * confirms (verified event), posts the money through {@link PaymentService#recordProviderPayment}. Browser redirects prove nothing.
 */
public interface OnlinePaymentService {

	/** Currencies a customer may pay online right now (empty when the integration is off or no currency passed the provider check). */
	Set<SupportedCurrency> availableCurrencies();

	/**
	 * Opens (or returns the open) hosted checkout for the FULL due amount of one currency. {@code access} proves the caller owns the reservation.
	 * Needs an idempotency key. The amount is computed here from the ledger, never supplied by the caller.
	 */
	Checkout createCheckout(ReservationAccess access, SupportedCurrency currency, String idempotencyKey);

	/** The real state of the reservation and its attempts, for the customer who owns it. */
	Status status(ReservationAccess access);

	/** Applies a verified provider event exactly once (duplicates and out-of-order deliveries are absorbed). */
	void handle(ProviderEvent event);

	/** Staff: attempts, optionally only those needing attention. */
	com.anadepot.core.shared.PageResult<PaymentAttempt> attempts(UUID reservationId, com.anadepot.core.payment.model.AttemptStatus status, Boolean requiresAttention, com.anadepot.core.shared.PageRequest page);

	/** Who is asking: a guest proves the phone number, a signed-in customer is identified by the verified token. */
	record ReservationAccess(String reference, String phone, UUID userId) {
	}

	record Checkout(PaymentAttempt attempt, boolean reused) {
	}

	record Status(Reservation reservation, List<PaymentAttempt> attempts) {
	}
}
