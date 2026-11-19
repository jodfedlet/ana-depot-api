package com.anadepot.core.reservation.serviceImpl;

import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.reservation.model.ItemKey;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationLine;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.PickupSnapshotProvider;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.reservation.service.ReservationService;
import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.ActorKind;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.notification.model.NotificationRequest;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.service.NotificationRecorder;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.OperationMetrics;
import com.anadepot.core.shared.TransactionBoundary;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Concurrency strategy (see docs/adr/0009): stock rows are locked with {@code SELECT ... FOR UPDATE} in a stable
 * order (ItemKey order) inside the use-case transaction, so two customers can never both take the last unit and two
 * transactions can never deadlock on the same set of rows. The reservation row is locked for every transition and
 * payment. Optimistic {@code version} columns and database constraints are a second line of defence.
 */
public class ReservationServiceImpl implements ReservationService {

	private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

	private final ReservationRepository reservations;
	private final InventoryRepository inventory;
	private final CatalogService catalog;
	private final CustomerRepository customers;
	private final AuditRecorder audit;
	private final DomainEventRecorder events;
	private final OperationMetrics metrics;
	private final NotificationRecorder notifications;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final PickupSnapshotProvider pickup;
	private final SecureRandom random = new SecureRandom();

	public ReservationServiceImpl(ReservationRepository reservations, InventoryRepository inventory, CatalogService catalog,
			CustomerRepository customers, AuditRecorder audit, DomainEventRecorder events, OperationMetrics metrics,
			IdempotencyService idempotency, TransactionBoundary tx, Clock clock, PickupSnapshotProvider pickup, NotificationRecorder notifications) {
		this.pickup = pickup;
		this.notifications = notifications;
		this.reservations = reservations;
		this.inventory = inventory;
		this.catalog = catalog;
		this.customers = customers;
		this.audit = audit;
		this.events = events;
		this.metrics = metrics;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
	}

	// ---- submit ---------------------------------------------------------------------------------------------

	@Override
	public Reservation submit(SubmitReservationCommand cmd) {
		if (cmd.lines() == null || cmd.lines().isEmpty()) {
			throw new DomainRuleViolation("cart_empty", "A reservation needs at least one line");
		}
		var fingerprint = Fingerprint.of(cmd.lines(), cmd.contact(), cmd.locale());
		return idempotency.run(cmd.idempotencyKey(), scope(cmd.actor()), "reservation.submit", fingerprint,
				() -> tx.inTransaction(() -> doSubmit(cmd).id()), this::get);
	}

	private Reservation doSubmit(SubmitReservationCommand cmd) {
		var now = clock.instant();
		// Pickup must be available; the CURRENT settings are frozen on the reservation. A caller-supplied snapshot (tests only) wins.
		var pickupSnapshot = cmd.pickupSnapshotJson() != null ? cmd.pickupSnapshotJson() : pickup.currentSnapshotJson();
		var lines = new ArrayList<ReservationLine>();
		var seen = new HashSet<String>();
		var needed = new TreeMap<ItemKey, Integer>();
		for (var req : cmd.lines()) {
			if (!seen.add(req.productId() + "|" + req.variantId() + "|" + req.currency())) {
				throw new DomainRuleViolation("duplicate_line", "Send one line per product, variant and currency");
			}
			var product = catalog.getProduct(req.productId());
			var applied = product.quote(req.variantId(), req.currency(), req.quantity());
			var line = new ReservationLine(UUID.randomUUID(), product.id(), req.variantId(), product.name().in(cmd.locale()), product.sku(),
					catalog.describeVariant(product, req.variantId(), cmd.locale()), product.unit(), req.quantity(), applied.unitPrice(),
					applied.kind(), applied.tierMinQuantity(), applied.lineTotal(req.quantity()));
			lines.add(line);
			// The same stock position is shared by every currency: quantities of one product add up.
			needed.merge(line.itemKey(), line.quantity(), Integer::sum);
		}
		for (var entry : needed.entrySet()) { // ItemKey order = lock order
			var item = lockItem(entry.getKey());
			inventory.save(item.reserve(entry.getValue()));
		}
		var contact = cmd.contact();
		var customer = customerOf(cmd.actor(), contact, now);
		var submitted = Reservation.submit(UUID.randomUUID(), newReference(), cmd.locale(), contact, customer.id(), lines, pickupSnapshot, now);
		submitted.acknowledgeTerms(cmd.termsVersion(), now);   // the terms were accepted by submitting (the REST layer refuses a request without acknowledgement)
		var reservation = reservations.save(submitted);
		record(reservation, "reservation.submitted", "RESERVATION_SUBMITTED", cmd.actor() == null ? Actor.guest() : cmd.actor(), null, null);
		return reservation;
	}

	/**
	 * Whose reservation is it? A signed-in customer's own REGISTERED record (created from the contact on the first reservation if
	 * they have none yet); a guest's GUEST record for that phone number. A guest is never matched to, or merged into, a registered
	 * customer, even with the same phone number: the person is not proven to be the same.
	 */
	private Customer customerOf(Actor actor, com.anadepot.core.reservation.model.Contact contact, Instant now) {
		var phone = Customer.normalizePhone(contact.phone());
		if (actor != null && actor.kind() == ActorKind.CUSTOMER && actor.userId() != null) {
			return customers.findByAuthUserId(actor.userId())
					.orElseGet(() -> customers.save(new Customer(UUID.randomUUID(), contact.firstName(), contact.lastName(), contact.dialCode(), phone, contact.email(), actor.userId(), now)));
		}
		return customers.findGuestByPhone(contact.dialCode(), phone)
				.orElseGet(() -> customers.save(new Customer(UUID.randomUUID(), contact.firstName(), contact.lastName(), contact.dialCode(), phone, contact.email(), null, now)));
	}

	// ---- transitions ----------------------------------------------------------------------------------------

	@Override
	public Reservation accept(UUID id, Actor actor, String key) {
		return transition(id, actor, key, "accept", "", r -> r.accept(actor, clock.instant()), "reservation.confirmed", "RESERVATION_ACCEPTED", null);
	}

	@Override
	public Reservation reject(UUID id, Actor actor, RejectReason reason, String note, String key) {
		return transition(id, actor, key, "reject", reason + "|" + note, r -> r.reject(actor, clock.instant(), reason, note), "reservation.rejected", "RESERVATION_REJECTED", reason == null ? null : reason.name());
	}

	@Override
	public Reservation markReady(UUID id, Actor actor, Instant expiresAt, String key) {
		return transition(id, actor, key, "ready", String.valueOf(expiresAt), r -> r.markReady(actor, clock.instant(), expiresAt), "reservation.ready_for_pickup", "RESERVATION_READY", null);
	}

	@Override
	public Reservation collect(UUID id, Actor actor, String key) {
		return transition(id, actor, key, "collect", "", r -> r.collect(actor, clock.instant()), "reservation.collected", "RESERVATION_COLLECTED", null);
	}

	@Override
	public Reservation cancel(UUID id, Actor actor, String reason, String key) {
		return transition(id, actor, key, "cancel", reason, r -> r.cancel(actor, clock.instant(), reason), "reservation.cancelled", "RESERVATION_CANCELLED", reason);
	}

	@Override
	public Reservation expire(UUID id, Actor actor, String key) {
		return transition(id, actor, key, "expire", "", r -> r.expire(actor, clock.instant()), "reservation.expired", "RESERVATION_EXPIRED", null);
	}

	@Override
	public Reservation get(UUID id) {
		return reservations.findById(id).orElseThrow(() -> new NotFoundException("Reservation", id));
	}

	@Override
	public Optional<Reservation> lookup(String reference, String phone) {
		if (reference == null || phone == null) {
			return Optional.empty();
		}
		var normalized = reference.strip().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
		return reservations.findByReference(normalized).filter(r -> r.phoneMatches(phone));
	}

	private Reservation transition(UUID id, Actor actor, String key, String op, String params, Consumer<Reservation> action,
			String eventType, String auditAction, String reason) {
		var fingerprint = Fingerprint.of(id, op, params);
		return idempotency.run(key, scope(actor), "reservation." + op, fingerprint, () -> tx.inTransaction(() -> {
			var reservation = reservations.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Reservation", id));
			var before = reservation.status();
			action.accept(reservation);
			applyStockEffects(reservation, before);
			reservations.save(reservation);
			record(reservation, eventType, auditAction, actor, before, reason);
			return reservation.id();
		}), this::get);
	}

	/** Leaving an open status without collecting releases the held stock; collecting hands the goods over. */
	private void applyStockEffects(Reservation reservation, ReservationStatus before) {
		var after = reservation.status();
		boolean release = before.isOpen() && (after == ReservationStatus.REJECTED || after == ReservationStatus.CANCELLED || after == ReservationStatus.EXPIRED);
		boolean handOver = before == ReservationStatus.READY_FOR_PICKUP && after == ReservationStatus.COLLECTED;
		if (!release && !handOver) {
			return;
		}
		for (var entry : reservation.heldQuantities().entrySet()) { // stable lock order
			var item = lockItem(entry.getKey());
			if (release) {
				inventory.save(item.release(entry.getValue()));
			} else {
				var next = item.handOver(entry.getValue());
				inventory.save(next);
				if (item.tracked()) {
					inventory.saveMovement(new StockMovement(UUID.randomUUID(), item.id(), MovementType.SALE, next.onHand() - item.onHand(), next.onHand(),
							StockReason.SALE, reservation.id(), reservation.reference(), clock.instant(), null));
				}
			}
		}
	}

	private InventoryItem lockItem(ItemKey key) {
		return inventory.findForUpdate(key.productId(), key.variantId())
				.orElseThrow(() -> new DomainRuleViolation("inventory_not_configured", "No stock position exists for product " + key.productId()));
	}

	/** The customer message of a reservation event; null for events customers are not mailed about (internal notes). */
	private static NotificationType notificationOf(String eventType) {
		return switch (eventType) {
			case "reservation.submitted" -> NotificationType.RESERVATION_SUBMITTED;
			case "reservation.confirmed" -> NotificationType.RESERVATION_CONFIRMED;
			case "reservation.rejected" -> NotificationType.RESERVATION_REJECTED;
			case "reservation.ready_for_pickup" -> NotificationType.RESERVATION_READY_FOR_PICKUP;
			case "reservation.collected" -> NotificationType.RESERVATION_COLLECTED;
			case "reservation.cancelled" -> NotificationType.RESERVATION_CANCELLED;
			case "reservation.expired" -> NotificationType.RESERVATION_EXPIRED;
			default -> null;
		};
	}

	private void record(Reservation r, String eventType, String auditAction, Actor actor, ReservationStatus before, String reason) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reference", r.reference());
		payload.put("status", r.status().name());
		payload.put("locale", r.locale());
		var totals = new LinkedHashMap<String, Object>();
		r.totals().amounts().forEach(m -> totals.put(m.currency().name(), m.amount().toPlainString()));
		payload.put("totals", totals); // no personal data in events: consumers load the reservation by id
		events.record(new DomainEvent(eventType, "reservation", r.id(), r.history().size(), payload));
		// The customer message is recorded in THIS transaction (it commits or rolls back with the operation) and is sent later by the dispatcher, never here.
		var notification = notificationOf(eventType);
		if (notification != null) {
			notifications.record(NotificationRequest.reservation(notification, r.id(), r.locale(), r.contact().email()));
		}
		Map<String, Object> previous = before == null ? Map.of() : Map.of("status", before.name());
		audit.record(AuditEvent.of(auditAction, "reservation", r.id(), actor, previous, Map.of("status", r.status().name(), "reference", r.reference()), reason));
		metrics.count("reservation." + r.status().name().toLowerCase());
	}

	private String newReference() {
		for (int attempt = 0; attempt < 20; attempt++) {
			var sb = new StringBuilder("ANA-");
			for (int i = 0; i < 6; i++) {
				sb.append(REF_ALPHABET.charAt(random.nextInt(REF_ALPHABET.length())));
			}
			if (!reservations.referenceExists(sb.toString())) {
				return sb.toString();
			}
		}
		throw new IllegalStateException("Could not generate a unique reservation reference");
	}

	static String scope(Actor actor) {
		if (actor == null || actor.kind() == ActorKind.CUSTOMER && actor.userId() == null) {
			return "anonymous";
		}
		return actor.kind().name().toLowerCase() + ":" + (actor.userId() == null ? "system" : actor.userId());
	}
}
