package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A reservation: the customer's request to collect goods at the store.
 *
 * <pre>
 * PENDING_REVIEW --accept--> CONFIRMED --markReady--> READY_FOR_PICKUP --collect--> COLLECTED
 *      | reject (reason)                                  (needs EVERY currency paid)
 *      +----------- cancel (reason) from any open status -------------> CANCELLED
 *                   expire (system, from CONFIRMED/READY; timing is NOT decided) -> EXPIRED
 * </pre>
 * Totals and balances are per currency ({@link CurrencyTotals}); there is no single total. Payment status is derived.
 * Open statuses hold stock; leaving them by reject/cancel/expire releases it, collecting hands it over.
 * Not modelled (undecided): who may do what (roles), the hold period, the customer-cancellation policy.
 */
public final class Reservation {

	private final UUID id;
	private final String reference;
	private final String locale;
	private final Contact contact;
	private final UUID customerId;
	private final List<ReservationLine> lines;
	private final CurrencyTotals totals;
	private final String pickupSnapshotJson;
	private final Instant createdAt;
	private ReservationStatus status;
	private CurrencyTotals paid;
	private Instant readyAt;
	private Instant expiresAt;
	private String termsVersion;
	private Instant termsAcceptedAt;
	private final List<StatusChange> history;
	private final List<ReservationNote> notes;

	public Reservation(UUID id, String reference, String locale, Contact contact, UUID customerId, List<ReservationLine> lines,
			CurrencyTotals totals, CurrencyTotals paid, String pickupSnapshotJson, Instant createdAt, ReservationStatus status,
			Instant readyAt, Instant expiresAt, List<StatusChange> history, List<ReservationNote> notes) {
		if (lines.isEmpty()) {
			throw new DomainRuleViolation("cart_empty", "A reservation needs at least one line");
		}
		if (pickupSnapshotJson == null || pickupSnapshotJson.isBlank()) {
			throw new DomainRuleViolation("pickup_required", "Pickup information must be snapshotted on the reservation");
		}
		var expected = CurrencyTotals.sum(lines.stream().map(ReservationLine::lineTotal).toList());
		if (!expected.equals(totals)) {
			throw new DomainRuleViolation("totals_mismatch", "Reservation totals must equal the per-currency sum of the lines");
		}
		this.id = id;
		this.reference = reference;
		this.locale = locale;
		this.contact = contact;
		this.customerId = customerId;
		this.lines = List.copyOf(lines);
		this.totals = totals;
		this.paid = paid;
		this.pickupSnapshotJson = pickupSnapshotJson;
		this.createdAt = createdAt;
		this.status = status;
		this.readyAt = readyAt;
		this.expiresAt = expiresAt;
		this.history = new ArrayList<>(history);
		this.notes = new ArrayList<>(notes);
	}

	/** A new reservation: always PENDING_REVIEW and unpaid, whatever the caller says. */
	public static Reservation submit(UUID id, String reference, String locale, Contact contact, UUID customerId,
			List<ReservationLine> lines, String pickupSnapshotJson, Instant now) {
		var totals = CurrencyTotals.sum(lines.stream().map(ReservationLine::lineTotal).toList());
		var created = new StatusChange(null, ReservationStatus.PENDING_REVIEW, null, null, now, Actor.guest());
		return new Reservation(id, reference, locale, contact, customerId, lines, totals, CurrencyTotals.empty(), pickupSnapshotJson, now,
				ReservationStatus.PENDING_REVIEW, null, null, List.of(created), List.of());
	}

	/** Records that the customer accepted the terms at {@code at}; {@code version} is the configured terms version (null when none is configured). Set once, at submission. */
	public void acknowledgeTerms(String version, Instant at) {
		this.termsVersion = version == null || version.isBlank() ? null : version.strip();
		this.termsAcceptedAt = at;
	}

	/** Restores what was persisted (adapter only). */
	public void restoreTerms(String version, Instant at) {
		this.termsVersion = version;
		this.termsAcceptedAt = at;
	}

	public String termsVersion() { return termsVersion; }
	public Instant termsAcceptedAt() { return termsAcceptedAt; }

	// ---- lifecycle -------------------------------------------------------------------------------------------

	public void accept(Actor actor, Instant now) {
		require(status == ReservationStatus.PENDING_REVIEW, "Only a pending reservation can be accepted");
		move(ReservationStatus.CONFIRMED, null, null, now, actor);
	}

	public void reject(Actor actor, Instant now, RejectReason reason, String note) {
		require(status == ReservationStatus.PENDING_REVIEW, "Only a pending reservation can be rejected");
		if (reason == null) {
			throw new DomainRuleViolation("reason_required", "A rejection needs a reason");
		}
		if (reason == RejectReason.OTHER && (note == null || note.isBlank())) {
			throw new DomainRuleViolation("explanation_required", "Rejecting for 'other' needs an explanation");
		}
		move(ReservationStatus.REJECTED, reason.name(), note, now, actor);
	}

	/** {@code expiresAt} stays null unless a hold period has been configured: the business has not decided one. */
	public void markReady(Actor actor, Instant now, Instant expiresAt) {
		require(status == ReservationStatus.CONFIRMED, "Only a confirmed reservation can be marked ready");
		this.readyAt = now;
		this.expiresAt = expiresAt;
		move(ReservationStatus.READY_FOR_PICKUP, null, null, now, actor);
	}

	public void collect(Actor actor, Instant now) {
		require(status == ReservationStatus.READY_FOR_PICKUP, "Only a ready reservation can be collected");
		if (paymentStatus() != PaymentStatus.PAID) {
			throw new DomainRuleViolation("payment_required", "Every currency must be paid before collection");
		}
		move(ReservationStatus.COLLECTED, null, null, now, actor);
	}

	public void cancel(Actor actor, Instant now, String reason) {
		require(status.isOpen(), "Only an open reservation can be cancelled");
		if (reason == null || reason.isBlank()) {
			throw new DomainRuleViolation("reason_required", "A cancellation needs a reason");
		}
		move(ReservationStatus.CANCELLED, "CANCELLED", reason.strip(), now, actor);
	}

	public void expire(Actor actor, Instant now) {
		require(status == ReservationStatus.CONFIRMED || status == ReservationStatus.READY_FOR_PICKUP, "Only a confirmed or ready reservation can expire");
		move(ReservationStatus.EXPIRED, "EXPIRED", null, now, actor);
	}

	public void addNote(NoteVisibility visibility, String body, Actor author, Instant now) {
		if (body == null || body.isBlank()) {
			throw new DomainRuleViolation("note_empty", "A note cannot be empty");
		}
		notes.add(new ReservationNote(UUID.randomUUID(), visibility, body.strip(), now, author));
	}

	// ---- money -----------------------------------------------------------------------------------------------

	/** One balance per currency of this reservation: total, paid (from the ledger) and due. */
	public Map<SupportedCurrency, Balance> balances() {
		var result = new TreeMap<SupportedCurrency, Balance>();
		for (var total : totals.amounts()) {
			var paidIn = paid.get(total.currency());
			result.put(total.currency(), new Balance(total, paidIn, total.minusFloorZero(paidIn)));
		}
		return result;
	}

	public PaymentStatus paymentStatus() {
		var all = balances().values();
		if (!all.isEmpty() && all.stream().allMatch(Balance::settled)) {
			return PaymentStatus.PAID;
		}
		return all.stream().anyMatch(b -> b.paid().isPositive()) ? PaymentStatus.PARTIAL : PaymentStatus.UNPAID;
	}

	/**
	 * Applies a signed ledger movement (+payment, -reversal, +/-adjustment) to the cached paid amount of ITS currency.
	 * The currency must be one of the reservation's; the result may not be negative. Overpayment is currently allowed
	 * (the due amount floors at zero): an unresolved business decision, kept as in the storefront.
	 */
	public void applyLedger(Money signed) {
		if (!totals.has(signed.currency())) {
			throw new DomainRuleViolation("currency_invalid", "This reservation has no " + signed.currency() + " balance");
		}
		var next = paid.get(signed.currency()).plus(signed);
		if (next.isNegative()) {
			throw new DomainRuleViolation("paid_negative", "Paid amount in " + signed.currency() + " cannot go below zero");
		}
		var rebuilt = CurrencyTotals.empty();
		for (var c : paid.currencies()) {
			if (c != signed.currency()) {
				rebuilt = rebuilt.plus(paid.get(c));
			}
		}
		this.paid = rebuilt.plus(next);
	}

	// ---- queries ---------------------------------------------------------------------------------------------

	/** Quantity this reservation holds per stock position (a product reserved in HTG and in USD on one reservation adds up). */
	public Map<ItemKey, Integer> heldQuantities() {
		var held = new TreeMap<ItemKey, Integer>();
		for (var l : lines) {
			held.merge(l.itemKey(), l.quantity(), Integer::sum);
		}
		return held;
	}

	private void require(boolean ok, String message) {
		if (!ok) {
			throw new DomainRuleViolation("invalid_status", message + " (current status: " + status + ")");
		}
	}

	private void move(ReservationStatus to, String code, String note, Instant now, Actor actor) {
		history.add(new StatusChange(status, to, code, note, now, actor));
		this.status = to;
	}

	/** Does this phone number identify the person who made the reservation? (see {@link Contact#phoneMatches}) */
	public boolean phoneMatches(String typed) {
		return contact.phoneMatches(typed);
	}

	public UUID id() { return id; }
	public String reference() { return reference; }
	public String locale() { return locale; }
	public Contact contact() { return contact; }
	public UUID customerId() { return customerId; }
	public List<ReservationLine> lines() { return lines; }
	public CurrencyTotals totals() { return totals; }
	public CurrencyTotals paid() { return paid; }
	public String pickupSnapshotJson() { return pickupSnapshotJson; }
	public Instant createdAt() { return createdAt; }
	public ReservationStatus status() { return status; }
	public Instant readyAt() { return readyAt; }
	public Instant expiresAt() { return expiresAt; }
	public List<StatusChange> history() { return List.copyOf(history); }
	public List<ReservationNote> notes() { return List.copyOf(notes); }
}
