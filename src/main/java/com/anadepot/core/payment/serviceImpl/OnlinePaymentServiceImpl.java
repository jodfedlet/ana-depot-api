package com.anadepot.core.payment.serviceImpl;

import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.core.payment.model.AttemptStatus;
import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.payment.model.ProviderEvent;
import com.anadepot.core.payment.repository.PaymentAttemptRepository;
import com.anadepot.core.payment.service.CheckoutGateway;
import com.anadepot.core.payment.service.OnlinePaymentService;
import com.anadepot.core.payment.service.PaymentService;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.OperationMetrics;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Online payment (docs/payments-stripe.md). Rules that must not be "simplified":
 * <ul>
 * <li>the amount is the FULL due of ONE currency, computed here from the ledger; nothing the browser says is used;</li>
 * <li>only a verified provider event posts money, once (decided from the attempt's status under the reservation lock; the database refuses a second payment per attempt);</li>
 * <li>a provider payment is ALWAYS posted, even when the reservation moved on, because the money moved: such a case is flagged for a human, not refused;</li>
 * <li>opening or abandoning a checkout never changes the reservation.</li>
 * </ul>
 */
public class OnlinePaymentServiceImpl implements OnlinePaymentService {

	private static final String PROVIDER = "STRIPE";

	private final PaymentAttemptRepository attempts;
	private final ReservationRepository reservations;
	private final CustomerRepository customers;
	private final PaymentService payments;
	private final CheckoutGateway gateway;
	private final IdempotencyService idempotency;
	private final OperationMetrics metrics;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final com.anadepot.core.notification.service.NotificationRecorder notifications;
	private final com.anadepot.core.shared.AuditRecorder audit;

	public OnlinePaymentServiceImpl(PaymentAttemptRepository attempts, ReservationRepository reservations, CustomerRepository customers, PaymentService payments,
			CheckoutGateway gateway, IdempotencyService idempotency, OperationMetrics metrics, TransactionBoundary tx, Clock clock,
			com.anadepot.core.notification.service.NotificationRecorder notifications, com.anadepot.core.shared.AuditRecorder audit) {
		this.audit = audit;
		this.notifications = notifications;
		this.attempts = attempts;
		this.reservations = reservations;
		this.customers = customers;
		this.payments = payments;
		this.gateway = gateway;
		this.idempotency = idempotency;
		this.metrics = metrics;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public CheckoutGateway.Availability availability() {
		return gateway.availability();
	}

	@Override
	public Set<SupportedCurrency> availableCurrencies() {
		return gateway.enabled() ? gateway.eligibleCurrencies() : Set.of();
	}

	@Override
	public Checkout createCheckout(ReservationAccess access, SupportedCurrency currency, String key) {
		if (key == null || key.isBlank()) {
			throw new DomainRuleViolation("idempotency_key_required", "An idempotency key is required to start an online payment");
		}
		if (!gateway.enabled()) {
			throw new DomainRuleViolation("online_payment_unavailable", "Online payment is not available");
		}
		if (!gateway.eligibleCurrencies().contains(currency)) {
			throw new DomainRuleViolation("currency_not_available_online", "Online payment is not available in " + currency);
		}
		var reservation = authorize(access);
		var scope = access.userId() != null ? "customer:" + access.userId() : "guest:" + reservation.id();
		return idempotency.run(key, scope, "payment.checkout", Fingerprint.of(reservation.id(), currency),
				() -> tx.inTransaction(() -> prepare(reservation.id(), currency, access.userId())), this::ensureSession);
	}

	/** Under the reservation lock: validates, and returns the id of the open attempt to use (existing and still payable, or a new one). */
	private UUID prepare(UUID reservationId, SupportedCurrency currency, UUID userId) {
		var reservation = reservations.findByIdForUpdate(reservationId).orElseThrow(() -> new NotFoundException("Reservation", reservationId));
		if (reservation.status() != ReservationStatus.READY_FOR_PICKUP) {
			throw new DomainRuleViolation("invalid_status", "Online payment opens when the store marks the reservation ready for pickup (current: " + reservation.status() + ")");
		}
		var balance = reservation.balances().get(currency);
		if (balance == null) {
			throw new DomainRuleViolation("currency_invalid", "This reservation has no " + currency + " balance");
		}
		if (balance.settled()) {
			throw new DomainRuleViolation("already_paid", "The " + currency + " balance is already settled");
		}
		var due = balance.due();
		gateway.minimumCharge(currency).filter(due::isLessThan).ifPresent(min -> {
			throw new DomainRuleViolation("amount_below_minimum", "The amount is below the minimum the payment provider accepts (" + min + ")");
		});
		var now = clock.instant();
		var open = attempts.findOpen(reservationId, currency.name());
		if (open.isPresent()) {
			var a = open.get();
			if (a.status() == AttemptStatus.PROCESSING) {
				throw new DomainRuleViolation("payment_in_progress", "A payment in " + currency + " is being processed by the provider");
			}
			if (a.amount().compareTo(due) == 0 && (a.sessionId() == null || a.payable(now))) {
				return a.id();
			}
			attempts.update(a.withStatus(AttemptStatus.EXPIRED, now)); // another amount is due now, or the session ran out: a new attempt replaces it
		}
		var attempt = PaymentAttempt.open(UUID.randomUUID(), reservationId, due, userId, now);
		attempts.insert(attempt);
		metrics.count("payment.attempt.opened", "currency", currency.name());
		return attempt.id();
	}

	/** Outside the reservation lock (a network call): creates the hosted session once; the provider call is idempotent per attempt, so a retry is safe. */
	private Checkout ensureSession(UUID attemptId) {
		var attempt = attempts.find(attemptId).orElseThrow(() -> new NotFoundException("Payment attempt", attemptId));
		if (attempt.sessionId() != null) {
			return new Checkout(attempt, true);
		}
		var reservation = reservations.findById(attempt.reservationId()).orElseThrow(() -> new NotFoundException("Reservation", attempt.reservationId()));
		var session = gateway.createSession(new CheckoutGateway.Request(attempt.id(), reservation.reference(), attempt.amount(), reservation.locale(),
				reservation.contact().email()));
		return new Checkout(attempts.update(attempt.withSession(session.sessionId(), session.url(), session.expiresAt(), clock.instant())), false);
	}

	@Override
	public Status status(ReservationAccess access) {
		var reservation = authorize(access);
		return new Status(reservation, attempts.forReservation(reservation.id(), 10));
	}

	@Override
	public PageResult<PaymentAttempt> attempts(UUID reservationId, AttemptStatus status, Boolean requiresAttention, PageRequest page) {
		return attempts.page(reservationId, status, requiresAttention, page);
	}

	/** The reservation, only for someone who proves ownership. Unknown reference, wrong phone and somebody else's reservation are the same answer. */
	private Reservation authorize(ReservationAccess access) {
		var reference = access.reference() == null ? "" : access.reference().strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
		return reservations.findByReference(reference).filter(r -> owns(r, access))
				.orElseThrow(() -> NotFoundException.withCode("reservation_not_found", "No reservation matches this reference and phone number."));
	}

	private boolean owns(Reservation r, ReservationAccess access) {
		if (access.userId() != null && r.customerId() != null) {
			var mine = customers.findByAuthUserId(access.userId());
			if (mine.isPresent() && mine.get().id().equals(r.customerId())) {
				return true;
			}
		}
		return access.phone() != null && r.phoneMatches(access.phone());
	}

	// ---- provider events -----------------------------------------------------------------------------------------

	@Override
	public void handle(ProviderEvent event) {
		tx.inTransaction(() -> {
			var found = locate(event);
			if (found == null) {
				if (!attempts.eventSeen(PROVIDER, event.eventId())) {
					attempts.recordEvent(PROVIDER, event.eventId(), event.kind().name(), null, "UNMATCHED", clock.instant());
				}
				return;
			}
			var reservation = reservations.findByIdForUpdate(found.reservationId()).orElseThrow(() -> new NotFoundException("Reservation", found.reservationId()));
			if (attempts.eventSeen(PROVIDER, event.eventId())) {
				return; // a duplicate delivery: already applied
			}
			var attempt = attempts.find(found.id()).orElseThrow(); // re-read under the lock
			var outcome = apply(event, attempt, reservation);
			attempts.recordEvent(PROVIDER, event.eventId(), event.kind().name(), attempt.id(), outcome, clock.instant());
			metrics.count("payment.provider.event", "kind", event.kind().name(), "outcome", outcome);
		});
	}

	private PaymentAttempt locate(ProviderEvent e) {
		if (e.sessionId() != null) {
			var bySession = attempts.findBySession(e.sessionId());
			if (bySession.isPresent()) {
				return bySession.get();
			}
		}
		if (e.paymentIntentId() != null) {
			var byIntent = attempts.findByPaymentIntent(e.paymentIntentId());
			if (byIntent.isPresent()) {
				return byIntent.get();
			}
		}
		if (e.attemptId() != null) {
			try {
				var byMetadata = attempts.find(UUID.fromString(e.attemptId()));
				// the metadata is only a hint: it may not point at an attempt that already belongs to a different session or intent
				if (byMetadata.isPresent() && (e.sessionId() == null || byMetadata.get().sessionId() == null || byMetadata.get().sessionId().equals(e.sessionId()))
						&& (e.paymentIntentId() == null || byMetadata.get().paymentIntentId() == null || byMetadata.get().paymentIntentId().equals(e.paymentIntentId()))) {
					return byMetadata.get();
				}
			} catch (IllegalArgumentException ignored) {
				// not an attempt id: unmatched
			}
		}
		return null;
	}

	private String apply(ProviderEvent e, PaymentAttempt attempt, Reservation reservation) {
		var now = clock.instant();
		var current = attempt.withPaymentIntent(e.paymentIntentId(), now);
		switch (e.kind()) {
			case SESSION_COMPLETED -> {
				return e.paid() ? settle(e, current, reservation) : markProcessing(current);
			}
			case SESSION_ASYNC_SUCCEEDED, PAYMENT_SUCCEEDED -> {
				return settle(e, current, reservation);
			}
			case SESSION_ASYNC_FAILED -> {
				return terminal(current, AttemptStatus.FAILED, e.failureCode(), reservation);
			}
			case SESSION_EXPIRED -> {
				return terminal(current, AttemptStatus.EXPIRED, null, reservation);
			}
			case PAYMENT_FAILED -> {
				// the customer may still retry inside the same session: only remember why, the attempt stays open
				attempts.update(current.withFailure(e.failureCode(), now));
				return "FAILURE_RECORDED";
			}
			case CHARGE_REFUNDED -> {
				return observeRefund(e, current, reservation);
			}
		}
		return "IGNORED";
	}

	/**
	 * D1: the owner refunded in the provider's dashboard. We only OBSERVE it: the cumulative refunded amount is stored on the attempt (so the staff screen's "refund due" clears) and
	 * audited. The ledger is not touched. A refund for an attempt that never succeeded, or in another currency, is flagged for a person instead of trusted.
	 */
	private String observeRefund(ProviderEvent e, PaymentAttempt attempt, Reservation reservation) {
		var now = clock.instant();
		if (attempt.status() != AttemptStatus.SUCCEEDED || attempt.paymentId() == null) {
			attempts.update(attempt.flagged("refund_without_payment", now));
			return "REFUND_FLAGGED";
		}
		if (e.amount() == null || e.currency() == null || !attempt.amount().currency().name().equalsIgnoreCase(e.currency())) {
			attempts.update(attempt.flagged("refund_amount_or_currency_mismatch", now));
			return "REFUND_FLAGGED";
		}
		if (e.amount().compareTo(attempt.refundedAmount()) <= 0) {
			return "REFUND_NOT_NEWER";   // an older or repeated notification: the amount only ever grows
		}
		var saved = attempts.update(attempt.withRefundObserved(e.amount(), now));
		audit.record(com.anadepot.core.shared.AuditEvent.of("PAYMENT_REFUND_OBSERVED", "reservation", reservation.id(), com.anadepot.core.shared.Actor.system(),
				java.util.Map.of("refunded", attempt.refundedAmount().toPlainString()), java.util.Map.of("refunded", saved.refundedAmount().toPlainString(), "currency", saved.amount().currency().name()), "observed from the provider"));
		return "REFUND_OBSERVED";
	}

	private String markProcessing(PaymentAttempt a) {
		if (a.status() != AttemptStatus.PENDING) {
			attempts.update(a);
			return "IGNORED_STATE";
		}
		attempts.update(a.withStatus(AttemptStatus.PROCESSING, clock.instant()));
		return "PROCESSING";
	}

	private String terminal(PaymentAttempt a, AttemptStatus next, String failureCode, Reservation reservation) {
		if (a.status() == AttemptStatus.SUCCEEDED) {
			attempts.update(a);
			return "IGNORED_SUCCEEDED"; // the money moved: a later failure or expiry notification can never undo it
		}
		if (a.status() == AttemptStatus.PENDING || a.status() == AttemptStatus.PROCESSING) {
			// The first verified provider event that ENDS this checkout tells the customer (once: the dedupe key is the attempt). A duplicate or a later contradictory event adds no second message.
			var type = next == AttemptStatus.FAILED ? com.anadepot.core.notification.model.NotificationType.PAYMENT_FAILED : com.anadepot.core.notification.model.NotificationType.CHECKOUT_EXPIRED;
			notifications.record(com.anadepot.core.notification.model.NotificationRequest.attempt(type, reservation.id(), a.id(), reservation.locale(), reservation.contact().email()));
		}
		var updated = a.withStatus(next, clock.instant());
		attempts.update(failureCode == null ? updated : updated.withFailure(failureCode, clock.instant()));
		return next.name();
	}

	/** Posts the verified money exactly once. */
	private String settle(ProviderEvent e, PaymentAttempt attempt, Reservation reservation) {
		var now = clock.instant();
		if (attempt.status() == AttemptStatus.SUCCEEDED) {
			attempts.update(attempt);
			return "DUPLICATE_STATE";
		}
		var currency = attempt.amount().currency();
		if (e.currency() == null || !currency.name().equalsIgnoreCase(e.currency()) || e.amount() == null || e.amount().compareTo(attempt.amount().amount()) != 0) {
			attempts.update(attempt.flagged("amount_mismatch", now)); // nothing is posted: a human compares the provider payment with the attempt
			return "AMOUNT_MISMATCH";
		}
		var balance = reservation.balances().get(currency);
		var reasons = new ArrayList<String>();
		if (reservation.status() != ReservationStatus.READY_FOR_PICKUP) {
			reasons.add("reservation_" + reservation.status().name().toLowerCase(Locale.ROOT));
		}
		if (balance == null || balance.settled()) {
			reasons.add("already_settled");
		} else if (balance.due().isLessThan(attempt.amount())) {
			reasons.add("overpaid");
		}
		var intent = e.paymentIntentId() != null ? e.paymentIntentId() : attempt.paymentIntentId();
		var payment = payments.recordProviderPayment(reservation.id(), attempt.amount(), intent);
		var done = attempt.succeeded(payment.id(), now);
		attempts.update(reasons.isEmpty() ? done : done.flagged(String.join(",", reasons), now));
		return "APPLIED";
	}
}
