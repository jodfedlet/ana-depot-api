package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationLine;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.ActorKind;
import com.anadepot.core.shared.money.Money;
import com.anadepot.infra.persistence.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WHAT A CUSTOMER MAY SEE of a reservation. A separate type from the domain object on purpose: it has no field for internal notes,
 * staff identities, actor user ids, ledger entries, payment references or staff-written rejection/cancellation text, so a controller
 * cannot leak them by accident. Money is a decimal string with its currency; there is no combined total across currencies.
 */
public record CustomerReservationView(UUID id, String reference, String status, String paymentStatus, String locale, Instant createdAt, Instant readyAt,
		Instant expiresAt, Contact contact, List<Line> lines, List<Amount> totals, List<Balance> balances, Map<String, Object> pickup,
		boolean canCancel, List<Event> events, List<PaymentLine> payments) {

	public record Contact(String firstName, String lastName, String dialCode, String phone, String email) {

		/** Personal data: never printed (a stray log statement or DEBUG logging must not leak it). */
		@Override
		public String toString() {
			return "Contact[redacted]";
		}
	}

	public record Amount(String currency, String amount) {
	}

	/**
	 * One payment of the reservation as the customer may see it: how much, in which currency, how it was paid and when. No staff identity, no reference, no note.
	 * {@code reversed}: a staff correction took it back (the balances above already reflect it).
	 */
	public record PaymentLine(String currency, String amount, String method, Instant receivedAt, boolean reversed) {
	}

	public record Balance(String currency, String total, String paid, String due) {
	}

	public record Line(UUID productId, UUID variantId, String name, String sku, String variantLabel, String unit, int quantity, String currency,
			String unitPrice, String pricingKind, Integer tierMinQuantity, String lineTotal) {
	}

	/** {@code type}: SUBMITTED, ACCEPTED, REJECTED, READY_FOR_PICKUP, COLLECTED, CANCELLED, EXPIRED, NOTE. */
	public record Event(String type, Instant at, String note, String rejectReason, String cancelledBy) {
	}

	/** Without the payment list (a reservation that was just created has none; list pages do not load one per row). */
	public static CustomerReservationView of(Reservation r) {
		return of(r, List.of());
	}

	public static CustomerReservationView of(Reservation r, List<PaymentLine> payments) {
		var lines = r.lines().stream().map(CustomerReservationView::line).toList();
		var totals = r.totals().amounts().stream().sorted(Comparator.comparing(m -> m.currency().name())).map(CustomerReservationView::amount).toList();
		var balances = r.balances().values().stream()
				.map(b -> new Balance(b.total().currency().name(), b.total().amount().toPlainString(), b.paid().amount().toPlainString(), b.due().amount().toPlainString())).toList();
		var contact = new Contact(r.contact().firstName(), r.contact().lastName(), r.contact().dialCode(), r.contact().phone(), r.contact().email());
		// canCancel is false until the business decides if and when a customer may cancel (open decision): there is no customer cancel endpoint
		return new CustomerReservationView(r.id(), r.reference(), r.status().name(), r.paymentStatus().name(), r.locale(), r.createdAt(), r.readyAt(), r.expiresAt(),
				contact, lines, totals, balances, Json.readMap(r.pickupSnapshotJson()), false, events(r), List.copyOf(payments));
	}

	private static Amount amount(Money m) {
		return new Amount(m.currency().name(), m.amount().toPlainString());
	}

	private static Line line(ReservationLine l) {
		return new Line(l.productId(), l.variantId(), l.productName(), l.productSku(), l.variantLabel(), l.unit().name(), l.quantity(), l.unitPrice().currency().name(),
				l.unitPrice().amount().toPlainString(), l.pricingKind().name(), l.tierMinQuantity(), l.lineTotal().amount().toPlainString());
	}

	/** Status changes (no actor, no free-form staff text) plus the notes the staff marked as customer-visible. */
	private static List<Event> events(Reservation r) {
		var events = new ArrayList<Event>();
		for (var h : r.history()) {
			var type = switch (h.to()) {
				case PENDING_REVIEW -> "SUBMITTED";
				case CONFIRMED -> "ACCEPTED";
				case REJECTED -> "REJECTED";
				case READY_FOR_PICKUP -> "READY_FOR_PICKUP";
				case COLLECTED -> "COLLECTED";
				case CANCELLED -> "CANCELLED";
				case EXPIRED -> "EXPIRED";
			};
			String rejectReason = h.to() == ReservationStatus.REJECTED ? h.reasonCode() : null;
			String cancelledBy = h.to() == ReservationStatus.CANCELLED ? (h.actor().kind() == ActorKind.CUSTOMER ? "CUSTOMER" : "STORE") : null;
			events.add(new Event(type, h.at(), null, rejectReason, cancelledBy));
		}
		for (var n : r.notes()) {
			if (n.visibility() == NoteVisibility.CUSTOMER_VISIBLE) {
				events.add(new Event("NOTE", n.at(), n.body(), null, null));
			}
		}
		events.sort(Comparator.comparing(Event::at));
		return events;
	}
}
