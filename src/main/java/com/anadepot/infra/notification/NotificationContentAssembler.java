package com.anadepot.infra.notification;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.repository.PaymentAttemptRepository;
import com.anadepot.core.payment.service.CheckoutGateway;
import com.anadepot.core.payment.service.OnlinePaymentService;
import com.anadepot.core.payment.service.PaymentAdminService;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.settings.model.PickupSettings;
import com.anadepot.core.settings.service.StoreSettingsService;
import com.anadepot.infra.persistence.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds what a message may show, at SEND time, from the committed records (never from the browser, never from a stale copy in the queue): the reservation's own snapshots, the payments
 * actually recorded, the pickup details as snapshotted on the reservation, and which payment methods exist right now. Read-only.
 */
@Component
class NotificationContentAssembler {

	/** The view plus the address to write to (resolved from the reservation's contact snapshot; it is not part of the view). */
	record Assembled(EmailView view, String to) {
	}

	private final ReservationRepository reservations;
	private final PaymentAdminService payments;
	private final PaymentAttemptRepository attempts;
	private final StoreSettingsService settings;
	private final OnlinePaymentService online;
	private final MailProperties mail;

	NotificationContentAssembler(ReservationRepository reservations, PaymentAdminService payments, PaymentAttemptRepository attempts, StoreSettingsService settings,
			OnlinePaymentService online, MailProperties mail) {
		this.reservations = reservations;
		this.payments = payments;
		this.attempts = attempts;
		this.settings = settings;
		this.online = online;
		this.mail = mail;
	}

	@Transactional(readOnly = true)
	Assembled assemble(Notification n) {
		var r = reservations.findById(n.reservationId()).orElseThrow(() -> new MailFailure("data_missing", true, null));
		var locale = "ht".equals(n.locale()) ? "ht" : "fr";
		var sentence = expectedStatus(n.type());
		if (sentence != null && r.status() != sentence) {
			// the message announces a state the reservation is no longer in (for example "ready" after it was already collected): never send a statement that is not true any more
			if (!(n.type() == NotificationType.RESERVATION_CONFIRMED && (r.status() == ReservationStatus.READY_FOR_PICKUP || r.status() == ReservationStatus.COLLECTED))) {
				throw new MailFailure("state_changed", true, null);
			}
		}
		var lines = new ArrayList<EmailView.Line>();
		for (var l : r.lines()) {
			lines.add(new EmailView.Line(l.productName(), l.variantLabel(), l.quantity(), l.unit().name(), EmailFormat.money(l.unitPrice()), EmailFormat.money(l.lineTotal()), l.pricingKind().name()));
		}
		var totals = new ArrayList<EmailView.Total>();
		r.totals().amounts().stream().sorted(java.util.Comparator.comparing(m -> m.currency().name())).forEach(m -> totals.add(new EmailView.Total(m.currency().name(), EmailFormat.money(m))));
		var balances = new ArrayList<EmailView.Balance>();
		r.balances().values().stream().sorted(java.util.Comparator.comparing(b -> b.total().currency().name()))
				.forEach(b -> balances.add(new EmailView.Balance(b.total().currency().name(), EmailFormat.money(b.total()), EmailFormat.money(b.paid()), EmailFormat.money(b.due()), !b.due().isPositive())));

		var pickupSettings = settings.getPickup();
		var pickup = pickupOf(r, locale);
		var support = supportOf(pickupSettings, pickup);

		var paymentList = new ArrayList<EmailView.Payment>();
		EmailView.Payment payment = null;
		var views = payments.forReservation(r.id());
		for (var v : views) {
			if (v.reversed()) {
				continue;   // a corrected payment is not shown as money received
			}
			var p = new EmailView.Payment(v.payment().amount().currency().name(), EmailFormat.money(v.payment().amount()), methodKey(v.payment().method()), EmailFormat.date(v.payment().receivedAt(), locale));
			paymentList.add(p);
			if (n.paymentId() != null && n.paymentId().equals(v.payment().id())) {
				payment = p;
			}
		}
		if (n.type() == NotificationType.PAYMENT_RECEIVED && payment == null) {
			throw new MailFailure("payment_not_found", true, null);   // never a receipt for money that is not in the ledger (or was reversed)
		}
		EmailView.Attempt attempt = null;
		if (n.attemptId() != null) {
			var a = attempts.find(n.attemptId()).orElseThrow(() -> new MailFailure("data_missing", true, null));
			attempt = new EmailView.Attempt(a.amount().currency().name(), EmailFormat.money(a.amount()));
		}
		var pickupOpen = pickupSettings.availability() == PickupSettings.Availability.AVAILABLE;
		var cardAvailable = pickupOpen && online.availability() == CheckoutGateway.Availability.AVAILABLE && !online.availableCurrencies().isEmpty();
		var base = mail.webBaseUrl() == null ? "" : mail.webBaseUrl().strip().replaceAll("/+$", "");
		var trackUrl = base.isEmpty() ? null : base + "/" + locale + "/reservations";
		var firstName = r.contact().firstName() == null ? "" : r.contact().firstName().strip();
		var view = new EmailView(locale, r.reference(), firstName, EmailFormat.date(r.createdAt(), locale), lines, totals, balances, pickup, trackUrl, reasonKey(n.type(), r), paymentList, payment,
				attempt, cardAvailable, pickupOpen, support, blankToNull(mail.termsUrl()), blankToNull(mail.privacyUrl()), mail.fromName());
		return new Assembled(view, r.contact().email());
	}

	/** The reservation status a message may only be sent for; null = any (payment messages describe money, not the reservation state). */
	private static ReservationStatus expectedStatus(NotificationType t) {
		return switch (t) {
			case RESERVATION_SUBMITTED -> null;           // always true once the reservation exists, whatever happened since
			case RESERVATION_CONFIRMED -> ReservationStatus.CONFIRMED;
			case RESERVATION_REJECTED -> ReservationStatus.REJECTED;
			case RESERVATION_READY_FOR_PICKUP -> ReservationStatus.READY_FOR_PICKUP;
			case RESERVATION_COLLECTED -> ReservationStatus.COLLECTED;
			case RESERVATION_CANCELLED -> ReservationStatus.CANCELLED;
			case RESERVATION_EXPIRED -> ReservationStatus.EXPIRED;
			default -> null;
		};
	}

	/** Only the machine code of a rejection, translated by the template. A staff-written note or cancellation reason is never shown to the customer. */
	private static String reasonKey(NotificationType type, Reservation r) {
		if (type != NotificationType.RESERVATION_REJECTED) {
			return null;
		}
		return r.history().stream().filter(h -> h.to() == ReservationStatus.REJECTED).reduce((a, b) -> b).map(h -> h.reasonCode()).filter(c -> c != null && c.matches("[A-Z_]{3,40}")).orElse("OTHER");
	}

	private static String methodKey(PaymentMethod m) {
		return m == PaymentMethod.STRIPE_CHECKOUT ? "email.method.card" : "email.method.cash";
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	/** The pickup details AS SNAPSHOTTED on the reservation (a later change of the store's settings never rewrites what the customer was promised). */
	@SuppressWarnings("unchecked")
	private static EmailView.Pickup pickupOf(Reservation r, String locale) {
		var json = r.pickupSnapshotJson();
		if (json == null || json.isBlank()) {
			return null;
		}
		Map<String, Object> snap = Json.readMap(json);
		var loc = snap.get("location") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
		if (loc == null || str(loc.get("storeName")).isEmpty()) {
			return null;
		}
		var address = String.join(", ", List.of(str(loc.get("addressLine")), str(loc.get("city")), str(loc.get("region"))).stream().filter(s -> !s.isEmpty()).toList());
		var hours = new ArrayList<String>();
		if (loc.get("schedule") instanceof List<?> days) {
			for (var d : days) {
				if (d instanceof Map<?, ?> dm && dm.get("day") instanceof Number num) {
					var open = Boolean.TRUE.equals(dm.get("open"));
					hours.add(EmailFormat.day(num.intValue(), locale) + " : " + (open ? str(dm.get("from")) + " – " + str(dm.get("to")) : ("ht".equals(locale) ? "Fèmen" : "Fermé")));
				}
			}
		}
		var instructions = snap.get("instructions") instanceof Map<?, ?> im ? (Map<String, Object>) im : Map.<String, Object>of();
		return new EmailView.Pickup(str(loc.get("storeName")), address, str(loc.get("phone")), str(loc.get("email")), hours, localized(loc.get("additionalLocationInfo"), locale),
				joinNonEmpty(localized(instructions.get("general"), locale), localized(instructions.get("ready"), locale)));
	}

	private static EmailView.Support supportOf(PickupSettings current, EmailView.Pickup snapshot) {
		var l = current.availability() == PickupSettings.Availability.AVAILABLE ? current.location() : null;
		var email = l != null ? l.email() : snapshot != null ? snapshot.email() : null;
		var phone = l != null ? l.phone() : snapshot != null ? snapshot.phone() : null;
		return new EmailView.Support(email, phone);
	}

	@SuppressWarnings("unchecked")
	private static String localized(Object value, String locale) {
		if (value instanceof Map<?, ?> m) {
			var map = (Map<String, Object>) m;
			var text = str(map.get(locale));
			return text.isEmpty() ? str(map.get("fr")) : text;
		}
		return "";
	}

	private static String joinNonEmpty(String... parts) {
		return String.join(" ", java.util.Arrays.stream(parts).filter(s -> s != null && !s.isEmpty()).toList());
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o).strip();
	}
}
