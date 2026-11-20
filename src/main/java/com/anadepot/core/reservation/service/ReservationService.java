package com.anadepot.core.reservation.service;

import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.Actor;
import java.time.Instant;
import java.util.Optional;
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

	/** {@code expiresAt} null = the pickup deadline from the hold policy (D6): ready time plus the hold period. */
	Reservation markReady(UUID id, Actor actor, Instant expiresAt, String idempotencyKey);

	/** Requires every currency paid; deducts physical stock and releases the reservation. */
	Reservation collect(UUID id, Actor actor, String idempotencyKey);

	/** Releases the reserved stock. */
	Reservation cancel(UUID id, Actor actor, String reason, String idempotencyKey);

	/** Releases the reserved stock. Done by the system when a ready reservation passes its pickup deadline (D6), or by staff. */
	Reservation expire(UUID id, Actor actor, String idempotencyKey);

	/**
	 * D7 (owner-approved): the CUSTOMER cancels their own reservation while it is pending review or confirmed, giving a reason. Ownership is proven exactly as for a lookup (reference AND
	 * phone); an unknown reference and a wrong phone answer the same way. Frees the reserved stock at once. Repeating it on an already cancelled reservation changes nothing.
	 */
	Reservation cancelByCustomer(String reference, String phone, Actor actor, String reason);

	/** The system expires every ready reservation past its deadline, one transaction each. @return how many expired. */
	int expireOverdue();

	/** The system queues the reminder for every ready reservation close to its deadline that was not reminded (once per reservation). @return how many reminders were queued. */
	int remindExpiring();

	Reservation get(UUID id);

	/**
	 * Guest lookup: the reservation whose reference AND phone number both match. Empty for an unknown reference and for a wrong
	 * phone alike, so callers cannot tell which one failed. The reference alone is never enough.
	 */
	Optional<Reservation> lookup(String reference, String phone);
}
