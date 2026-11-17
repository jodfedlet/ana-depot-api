package com.anadepot.core.payment.repository;

import com.anadepot.core.payment.model.AttemptStatus;
import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {

	PaymentAttempt insert(PaymentAttempt attempt);

	/** Updates the mutable fields of an existing attempt. */
	PaymentAttempt update(PaymentAttempt attempt);

	Optional<PaymentAttempt> find(UUID id);

	Optional<PaymentAttempt> findBySession(String sessionId);

	Optional<PaymentAttempt> findByPaymentIntent(String paymentIntentId);

	/** The open (PENDING/PROCESSING) attempt of this reservation and currency, if any. */
	Optional<PaymentAttempt> findOpen(UUID reservationId, String currency);

	/** Newest first. */
	List<PaymentAttempt> forReservation(UUID reservationId, int limit);

	PageResult<PaymentAttempt> page(UUID reservationId, AttemptStatus status, Boolean requiresAttention, PageRequest page);

	/** Records a handled provider event; false when this event id was already recorded (a duplicate delivery). */
	boolean recordEvent(String provider, String eventId, String eventType, UUID attemptId, String outcome, java.time.Instant at);

	boolean eventSeen(String provider, String eventId);
}
