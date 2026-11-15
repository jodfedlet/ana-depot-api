package com.anadepot.core.reservation.service;

import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.Actor;
import java.time.Instant;
import java.util.UUID;

/**
 * Reservation use cases. Each one is a single transaction that (1) locks what it changes, (2) applies the domain
 * rule, (3) moves stock, and (4) records the audit entry and the outbox event, all atomically. Every command accepts an
 * idempotency key: a retry with the same key returns the original result and has no further effect.
 */
public interface ReservationService {

	/** Validates, prices (server side), checks and reserves stock under row locks, and stores the reservation. */
	Reservation submit(SubmitReservationCommand command);

	Reservation accept(UUID id, Actor actor, String idempotencyKey);

	/** Releases the reserved stock. */
	Reservation reject(UUID id, Actor actor, RejectReason reason, String note, String idempotencyKey);

	/** {@code expiresAt} is null unless a hold period is configured (undecided). */
	Reservation markReady(UUID id, Actor actor, Instant expiresAt, String idempotencyKey);

	/** Requires every currency paid; deducts physical stock and releases the reservation. */
	Reservation collect(UUID id, Actor actor, String idempotencyKey);

	/** Releases the reserved stock. */
	Reservation cancel(UUID id, Actor actor, String reason, String idempotencyKey);

	/** Releases the reserved stock. Triggered by the system once a hold policy exists. */
	Reservation expire(UUID id, Actor actor, String idempotencyKey);

	Reservation get(UUID id);
}
