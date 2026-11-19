package com.anadepot.core.payment.serviceImpl;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.LedgerEntryType;
import com.anadepot.core.payment.model.Payment;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.PaymentService;
import com.anadepot.core.payment.service.ReconciliationReport;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.ActorKind;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.CorrelationContext;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.OperationMetrics;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Posting boundary (docs/adr/0001): a ledger entry is written for money received, reversed or adjusted — and only
 * then. Reservation status changes, stock movements and catalog edits are NOT financial events and write no entry.
 * The reservation row is locked for the whole operation, so two concurrent payments cannot both see the same due amount.
 */
public class PaymentServiceImpl implements PaymentService {

	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final AuditRecorder audit;
	private final DomainEventRecorder events;
	private final OperationMetrics metrics;
	private final CorrelationContext correlation;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final com.anadepot.core.notification.service.NotificationRecorder notifications;

	public PaymentServiceImpl(PaymentRepository payments, ReservationRepository reservations, AuditRecorder audit, DomainEventRecorder events,
			OperationMetrics metrics, CorrelationContext correlation, IdempotencyService idempotency, TransactionBoundary tx, Clock clock,
			com.anadepot.core.notification.service.NotificationRecorder notifications) {
		this.notifications = notifications;
		this.payments = payments;
		this.reservations = reservations;
		this.audit = audit;
		this.events = events;
		this.metrics = metrics;
		this.correlation = correlation;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public Payment record(RecordPaymentCommand cmd) {
		requireKey(cmd.idempotencyKey());
		if (cmd.method() == PaymentMethod.STRIPE_CHECKOUT) {
			throw new DomainRuleViolation("method_not_allowed", "Online payments are recorded only from a verified provider confirmation");
		}
		var fingerprint = Fingerprint.of(cmd.reservationId(), cmd.amount(), cmd.method(), cmd.receivedAt(), cmd.reference(), cmd.note());
		return idempotency.run(cmd.idempotencyKey(), scope(cmd.actor()), "payment.record", fingerprint, () -> tx.inTransaction(() -> {
			var reservation = lock(cmd.reservationId());
			requireReady(reservation);
			var balance = reservation.balances().get(cmd.amount().currency());
			if (balance == null) {
				throw new DomainRuleViolation("currency_invalid", "This reservation has no " + cmd.amount().currency() + " balance");
			}
			if (balance.settled()) {
				throw new DomainRuleViolation("already_paid", "The " + cmd.amount().currency() + " balance is already settled");
			}
			var now = clock.instant();
			var payment = new Payment(UUID.randomUUID(), reservation.id(), cmd.amount(), cmd.method(), cmd.receivedAt() == null ? now : cmd.receivedAt(), now,
					cmd.actor() == null ? null : cmd.actor().userId(), blankToNull(cmd.reference()), blankToNull(cmd.note()));
			reservation.applyLedger(cmd.amount());
			reservations.save(reservation);
			payments.savePayment(payment);
			payments.saveLedgerEntry(new LedgerEntry(UUID.randomUUID(), reservation.id(), payment.id(), LedgerEntryType.PAYMENT_RECEIVED, cmd.amount(), null, null, now,
					payment.recordedBy(), correlation.correlationId()));
			publish(reservation, payment.id(), "payment.recorded", "PAYMENT_RECORDED", cmd.actor(), cmd.amount(), null);
			return payment.id();
		}), id -> payments.findPayment(id).orElseThrow(() -> new NotFoundException("Payment", id)));
	}

	@Override
	public Payment recordProviderPayment(UUID reservationId, Money amount, String providerReference) {
		// No idempotency wrapper on purpose: the caller already holds the reservation lock and decides "once" from the attempt's state under that lock
		// (and the database refuses a second receipt for the same payment); a REQUIRES_NEW idempotency transaction would wait for that very lock.
		return tx.inTransaction(() -> {
			var reservation = lock(reservationId);
			var now = clock.instant();
			var payment = new Payment(UUID.randomUUID(), reservation.id(), amount, PaymentMethod.STRIPE_CHECKOUT, now, now, null, blankToNull(providerReference), null);
			reservation.applyLedger(amount);
			reservations.save(reservation);
			payments.savePayment(payment);
			payments.saveLedgerEntry(new LedgerEntry(UUID.randomUUID(), reservation.id(), payment.id(), LedgerEntryType.PAYMENT_RECEIVED, amount, null, null, now, null,
					correlation.correlationId()));
			publish(reservation, payment.id(), "payment.recorded", "PAYMENT_RECORDED", Actor.system(), amount, null);
			return payment;
		});
	}

	@Override
	public Payment reverse(UUID paymentId, String reason, Actor actor, String key) {
		requireKey(key);
		if (reason == null || reason.isBlank()) {
			throw new DomainRuleViolation("reason_required", "A reversal needs a reason");
		}
		var fingerprint = Fingerprint.of(paymentId, reason);
		return idempotency.run(key, scope(actor), "payment.reverse", fingerprint, () -> tx.inTransaction(() -> {
			var payment = payments.findPayment(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
			if (payment.method() == PaymentMethod.STRIPE_CHECKOUT) {
				throw new DomainRuleViolation("online_payment_reversal_unsupported", "An online payment cannot be reversed in the ledger: that would not return the money. Refunds need an approved policy.");
			}
			var reservation = lock(payment.reservationId());
			requireReady(reservation);
			var received = payments.receivedEntryOf(paymentId).orElseThrow(() -> new DomainRuleViolation("ledger_inconsistent", "Payment has no ledger entry"));
			if (payments.isReversed(received.id())) {
				throw new DomainRuleViolation("already_reversed", "This payment was already reversed");
			}
			var negative = payment.amount().negate();
			reservation.applyLedger(negative);
			reservations.save(reservation);
			payments.saveLedgerEntry(new LedgerEntry(UUID.randomUUID(), reservation.id(), paymentId, LedgerEntryType.PAYMENT_REVERSAL, negative, received.id(),
					reason.strip(), clock.instant(), actor == null ? null : actor.userId(), correlation.correlationId()));
			publish(reservation, paymentId, "payment.reversed", "PAYMENT_REVERSED", actor, negative, reason.strip());
			return paymentId;
		}), id -> payments.findPayment(id).orElseThrow(() -> new NotFoundException("Payment", id)));
	}

	@Override
	public LedgerEntry adjust(UUID reservationId, Money signed, String reason, Actor actor, String key) {
		requireKey(key);
		var fingerprint = Fingerprint.of(reservationId, signed, reason);
		return idempotency.run(key, scope(actor), "payment.adjust", fingerprint, () -> tx.inTransaction(() -> {
			var reservation = lock(reservationId);
			requireReady(reservation);
			var entry = new LedgerEntry(UUID.randomUUID(), reservationId, null, LedgerEntryType.ADJUSTMENT, signed, null, reason == null ? null : reason.strip(),
					clock.instant(), actor == null ? null : actor.userId(), correlation.correlationId());
			reservation.applyLedger(signed);
			reservations.save(reservation);
			payments.saveLedgerEntry(entry);
			publish(reservation, entry.id(), "payment.adjusted", "PAYMENT_ADJUSTED", actor, signed, reason);
			return entry.id();
		}), id -> payments.findLedgerEntry(id).orElseThrow(() -> new NotFoundException("Ledger entry", id)));
	}

	@Override
	public ReconciliationReport reconcile(UUID reservationId) {
		var reservation = reservations.findById(reservationId).orElseThrow(() -> new NotFoundException("Reservation", reservationId));
		var ledgerTotals = CurrencyTotals.sum(payments.ledger(reservationId).stream().map(LedgerEntry::amount).toList());
		var lines = reservation.totals().currencies().stream()
				.map(c -> new ReconciliationReport.Line(c, reservation.paid().get(c), ledgerTotals.get(c))).toList();
		return new ReconciliationReport(reservationId, lines);
	}

	private Reservation lock(UUID id) {
		return reservations.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Reservation", id));
	}

	private void requireReady(Reservation r) {
		if (r.status() != ReservationStatus.READY_FOR_PICKUP) {
			throw new DomainRuleViolation("invalid_status", "Payments are recorded while the reservation is ready for pickup (current: " + r.status() + ")");
		}
	}

	private static void requireKey(String key) {
		if (key == null || key.isBlank()) {
			throw new DomainRuleViolation("idempotency_key_required", "An idempotency key is required for financial operations");
		}
	}

	private void publish(Reservation r, UUID subjectId, String eventType, String auditAction, Actor actor, Money amount, String reason) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("reservationReference", r.reference());
		payload.put("subjectId", subjectId.toString());
		payload.put("amount", amount.amount().toPlainString());
		payload.put("currency", amount.currency().name());
		payload.put("paymentStatus", r.paymentStatus().name());
		events.record(new DomainEvent(eventType, "reservation", r.id(), r.history().size(), payload));
		if ("payment.recorded".equals(eventType)) {
			// A payment RECORDED in the ledger (cash by staff, or a verified provider payment): this is the only source of a receipt, so no receipt can exist for unconfirmed money.
			notifications.record(com.anadepot.core.notification.model.NotificationRequest.payment(r.id(), subjectId, r.locale(), r.contact().email()));
		}
		var current = new LinkedHashMap<String, Object>();
		current.put("amount", amount.toString());
		current.put("paymentStatus", r.paymentStatus().name());
		audit.record(AuditEvent.of(auditAction, "reservation", r.id(), actor == null ? Actor.system() : actor, java.util.Map.of(), current, reason));
		metrics.count("payment." + eventType.substring(eventType.indexOf('.') + 1), "currency", amount.currency().name());
	}

	private static String scope(Actor actor) {
		return actor == null || actor.userId() == null ? (actor != null && actor.kind() == ActorKind.SYSTEM ? "system" : "anonymous") : actor.kind().name().toLowerCase() + ":" + actor.userId();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}
}
