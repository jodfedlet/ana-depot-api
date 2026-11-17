package com.anadepot.core.reservation.service;

import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationQuery;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.UUID;

/**
 * Staff reading and annotating of reservations. The lifecycle transitions (accept, reject, ready, collect, cancel) stay in {@link ReservationService}, which
 * moves stock, writes the audit entry and the outbox event in one transaction.
 */
public interface ReservationAdminService {

	PageResult<Reservation> list(ReservationQuery query, PageRequest page);

	Reservation get(UUID id);

	/**
	 * Adds a note. INTERNAL notes are staff-only; CUSTOMER_VISIBLE notes appear in the customer's view. {@code idempotencyKey} optional: a retry with the
	 * same key does not add the note twice.
	 */
	Reservation addNote(UUID id, NoteVisibility visibility, String body, Actor actor, String idempotencyKey);
}
