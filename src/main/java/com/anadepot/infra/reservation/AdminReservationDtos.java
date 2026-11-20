package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.PaymentStatus;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationNote;
import com.anadepot.core.reservation.model.StatusChange;
import com.anadepot.core.shared.Actor;
import com.anadepot.infra.persistence.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What STAFF see of a reservation: everything the customer sees plus internal notes, actors, terms acknowledgement and the actions the current state allows. Money is decimal text per currency. */
final class AdminReservationDtos {

	private AdminReservationDtos() {
	}

	// ---- requests ----

	record RejectRequest(String reason, String note) {
	}

	record ReadyRequest(Instant expiresAt) {
	}

	record CancelRequest(String reason) {
	}

	record NoteRequest(String visibility, String body) {
	}

	// ---- responses ----

	record Contact(String firstName, String lastName, String dialCode, String phone, String email) {

		/** Personal data: never printed (a stray log statement or DEBUG logging must not leak it). */
		@Override
		public String toString() {
			return "Contact[redacted]";
		}
	}

	record Balance(String currency, String total, String paid, String due) {
	}

	/** One amount in one currency (never summed across currencies). */
	record Amount(String currency, String amount) {
	}

	record Line(UUID id, UUID productId, UUID variantId, String name, String sku, String variantLabel, String unit, int quantity, String currency, String unitPrice, String pricingKind,
			Integer tierMinQuantity, String lineTotal) {
	}

	record Who(String kind, UUID userId) {
	}

	record Change(String from, String to, String reasonCode, String reasonNote, Instant at, Who actor) {
	}

	record Note(UUID id, String visibility, String body, Instant at, Who author) {
	}

	record Terms(String version, Instant acceptedAt) {
	}

	record Summary(UUID id, String reference, String status, String paymentStatus, Instant createdAt, Contact contact, int lineCount, int unitCount, List<Balance> balances) {
	}

	record Detail(UUID id, String reference, String status, String paymentStatus, String locale, Instant createdAt, Instant readyAt, Instant expiresAt, UUID customerId, Contact contact,
			List<Line> lines, List<Balance> balances, Map<String, Object> pickup, Terms terms, List<Change> history, List<Note> notes, List<String> allowedActions, List<Amount> refundDue, List<Amount> refundedInProvider) {
	}

	// ---- mapping ----

	static Summary summary(Reservation r) {
		return new Summary(r.id(), r.reference(), r.status().name(), r.paymentStatus().name(), r.createdAt(), contact(r), r.lines().size(), r.lines().stream().mapToInt(l -> l.quantity()).sum(), balances(r));
	}

	static Detail detail(Reservation r, com.anadepot.core.payment.service.PaymentAdminService.RefundPosition refunds) {
		var terms = r.termsAcceptedAt() == null ? null : new Terms(r.termsVersion(), r.termsAcceptedAt());
		return new Detail(r.id(), r.reference(), r.status().name(), r.paymentStatus().name(), r.locale(), r.createdAt(), r.readyAt(), r.expiresAt(), r.customerId(), contact(r),
				r.lines().stream().map(l -> new Line(l.id(), l.productId(), l.variantId(), l.productName(), l.productSku(), l.variantLabel(), l.unit().name(), l.quantity(),
						l.unitPrice().currency().name(), l.unitPrice().amount().toPlainString(), l.pricingKind().name(), l.tierMinQuantity(), l.lineTotal().amount().toPlainString())).toList(),
				balances(r), Json.readMap(r.pickupSnapshotJson()), terms, r.history().stream().map(AdminReservationDtos::change).toList(),
				r.notes().stream().sorted(Comparator.comparing(ReservationNote::at)).map(AdminReservationDtos::note).toList(), allowedActions(r),
				refunds.due().stream().map(m -> new Amount(m.currency().name(), m.amount().toPlainString())).toList(),
				refunds.observedRefunded().stream().map(m -> new Amount(m.currency().name(), m.amount().toPlainString())).toList());
	}

	/** What the current state lets staff do next (the server still enforces every rule; this only saves the screen from guessing). */
	static List<String> allowedActions(Reservation r) {
		var actions = new ArrayList<String>();
		switch (r.status()) {
			case PENDING_REVIEW -> {
				actions.add("ACCEPT");
				actions.add("REJECT");
			}
			case CONFIRMED -> actions.add("MARK_READY");
			case READY_FOR_PICKUP -> {
				if (r.paymentStatus() == PaymentStatus.PAID) {
					actions.add("COLLECT");
				}
			}
			default -> {
			}
		}
		if (r.status().isOpen()) {
			actions.add("CANCEL");
		}
		actions.add("ADD_NOTE");
		return actions;
	}

	private static Contact contact(Reservation r) {
		var c = r.contact();
		return new Contact(c.firstName(), c.lastName(), c.dialCode(), c.phone(), c.email());
	}

	private static List<Balance> balances(Reservation r) {
		return r.balances().values().stream().map(b -> new Balance(b.total().currency().name(), b.total().amount().toPlainString(), b.paid().amount().toPlainString(), b.due().amount().toPlainString())).toList();
	}

	private static Who who(Actor a) {
		return new Who(a.kind().name(), a.userId());
	}

	private static Change change(StatusChange h) {
		return new Change(h.from() == null ? null : h.from().name(), h.to().name(), h.reasonCode(), h.reasonNote(), h.at(), who(h.actor()));
	}

	private static Note note(ReservationNote n) {
		return new Note(n.id(), n.visibility().name(), n.body(), n.at(), who(n.author()));
	}


}
