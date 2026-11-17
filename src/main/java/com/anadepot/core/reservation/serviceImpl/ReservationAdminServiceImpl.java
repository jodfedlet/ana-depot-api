package com.anadepot.core.reservation.serviceImpl;

import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationQuery;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.reservation.service.ReservationAdminService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public class ReservationAdminServiceImpl implements ReservationAdminService {

	static final int MAX_NOTE = 2000;

	private final ReservationRepository reservations;
	private final AuditRecorder audit;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;

	public ReservationAdminServiceImpl(ReservationRepository reservations, AuditRecorder audit, IdempotencyService idempotency, TransactionBoundary tx, Clock clock) {
		this.reservations = reservations;
		this.audit = audit;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public PageResult<Reservation> list(ReservationQuery query, PageRequest page) {
		return tx.inTransaction(() -> reservations.findPage(query, page));
	}

	@Override
	public Reservation get(UUID id) {
		return tx.inTransaction(() -> reservations.findById(id).orElseThrow(() -> new NotFoundException("Reservation", id)));
	}

	@Override
	public Reservation addNote(UUID id, NoteVisibility visibility, String body, Actor actor, String idempotencyKey) {
		if (visibility == null) {
			throw new DomainRuleViolation("visibility_required", "A note is either internal or visible to the customer");
		}
		if (body != null && body.strip().length() > MAX_NOTE) {
			throw new DomainRuleViolation("note_too_long", "A note is limited to " + MAX_NOTE + " characters");
		}
		var fingerprint = Fingerprint.of(id, visibility, body);
		return idempotency.run(idempotencyKey, "staff:" + (actor == null || actor.userId() == null ? "system" : actor.userId()), "reservation.note", fingerprint, () -> tx.inTransaction(() -> {
			var reservation = reservations.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Reservation", id));
			reservation.addNote(visibility, body, actor, clock.instant());
			reservations.save(reservation);
			// the text of a note is never copied into the audit trail: it may hold personal details
			audit.record(AuditEvent.of("RESERVATION_NOTE_ADDED", "reservation", id, actor, Map.of(), Map.of("visibility", visibility.name(), "length", body.strip().length()), null));
			return reservation.id();
		}), this::get);
	}
}
