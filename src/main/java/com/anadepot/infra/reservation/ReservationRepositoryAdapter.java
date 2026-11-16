package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.ItemKey;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationLine;
import com.anadepot.core.reservation.model.ReservationNote;
import com.anadepot.core.reservation.model.StatusChange;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class ReservationRepositoryAdapter implements ReservationRepository {

	private final ReservationJpaRepository jpa;
	private final Clock clock;

	ReservationRepositoryAdapter(ReservationJpaRepository jpa, Clock clock) {
		this.jpa = jpa;
		this.clock = clock;
	}

	@Override
	public Reservation save(Reservation r) {
		var existing = jpa.findById(r.id()).orElse(null);
		var e = existing == null ? create(r) : existing;
		e.status = r.status();
		e.readyAt = r.readyAt();
		e.expiresAt = r.expiresAt();
		e.updatedAt = clock.instant();
		// paid amounts are the cache of the ledger: refresh per currency
		for (var t : e.totals) {
			t.paidAmount = r.paid().get(t.currency).amount();
		}
		// history and notes are append-only: add only what the entity does not have yet
		var history = r.history();
		for (int i = e.history.size(); i < history.size(); i++) {
			var h = history.get(i);
			var x = new ReservationStatusHistoryEntity();
			x.id = UUID.randomUUID();
			x.reservation = e;
			x.seq = i + 1;
			x.fromStatus = h.from();
			x.toStatus = h.to();
			x.reasonCode = h.reasonCode();
			x.reasonNote = h.reasonNote();
			x.changedAt = h.at();
			x.actorKind = h.actor().kind();
			x.actorUserId = h.actor().userId();
			e.history.add(x);
		}
		var knownNotes = new HashSet<UUID>();
		e.notes.forEach(n -> knownNotes.add(n.id));
		for (var n : r.notes()) {
			if (knownNotes.add(n.id())) {
				var x = new ReservationNoteEntity();
				x.id = n.id();
				x.reservation = e;
				x.visibility = n.visibility();
				x.body = n.body();
				x.authorKind = n.author().kind();
				x.authorUserId = n.author().userId();
				x.createdAt = n.at();
				e.notes.add(x);
			}
		}
		return toDomain(jpa.saveAndFlush(e));
	}

	private ReservationEntity create(Reservation r) {
		var e = new ReservationEntity();
		e.id = r.id();
		e.reference = r.reference();
		e.locale = r.locale();
		e.customerId = r.customerId();
		e.contactFirstName = r.contact().firstName();
		e.contactLastName = r.contact().lastName();
		e.contactDialCode = r.contact().dialCode();
		e.contactPhone = r.contact().phone();
		e.contactEmail = r.contact().email();
		e.pickupSnapshot = r.pickupSnapshotJson();
		e.createdAt = r.createdAt();
		for (var total : r.totals().amounts()) {
			var t = new ReservationTotalEntity();
			t.id = UUID.randomUUID();
			t.reservation = e;
			t.currency = total.currency();
			t.totalAmount = total.amount();
			t.paidAmount = r.paid().get(total.currency()).amount();
			e.totals.add(t);
		}
		int position = 0;
		for (var l : r.lines()) {
			var x = new ReservationLineEntity();
			x.id = l.id();
			x.reservation = e;
			x.position = position++;
			x.productId = l.productId();
			x.variantId = l.variantId();
			x.productName = l.productName();
			x.productSku = l.productSku();
			x.variantLabel = l.variantLabel();
			x.sellingUnit = l.unit();
			x.quantity = l.quantity();
			x.currency = l.unitPrice().currency();
			x.unitPrice = l.unitPrice().amount();
			x.pricingKind = l.pricingKind();
			x.tierMinQuantity = l.tierMinQuantity();
			x.lineTotal = l.lineTotal().amount();
			e.lines.add(x);
		}
		return e;
	}

	@Override
	public Optional<Reservation> findById(UUID id) {
		return jpa.findById(id).map(ReservationRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Reservation> findByIdForUpdate(UUID id) {
		return jpa.lockById(id).map(ReservationRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Reservation> findByReference(String reference) {
		return jpa.findByReference(reference).map(ReservationRepositoryAdapter::toDomain);
	}

	@Override
	public boolean referenceExists(String reference) {
		return jpa.existsByReference(reference);
	}

	@Override
	public PageResult<Reservation> findByCustomerId(UUID customerId, PageRequest page) {
		var found = jpa.findByCustomerIdOrderByCreatedAtDescIdAsc(customerId, org.springframework.data.domain.PageRequest.of(page.page(), page.size()));
		return new PageResult<>(found.getContent().stream().map(ReservationRepositoryAdapter::toDomain).toList(), page.page(), page.size(), found.getTotalElements());
	}

	@Override
	public int openHeldQuantity(ItemKey key) {
		return (int) (key.variantId() == null ? jpa.openHeldForProduct(key.productId()) : jpa.openHeldForVariant(key.variantId()));
	}

	private static Reservation toDomain(ReservationEntity e) {
		var lines = new ArrayList<ReservationLine>();
		for (var l : e.lines) {
			lines.add(new ReservationLine(l.id, l.productId, l.variantId, l.productName, l.productSku, l.variantLabel, l.sellingUnit, l.quantity,
					Money.of(l.unitPrice, l.currency), l.pricingKind, l.tierMinQuantity, Money.of(l.lineTotal, l.currency)));
		}
		var totals = CurrencyTotals.sum(e.totals.stream().map(t -> Money.of(t.totalAmount, t.currency)).toList());
		var paid = CurrencyTotals.sum(e.totals.stream().map(t -> Money.of(t.paidAmount, t.currency)).toList());
		List<StatusChange> history = e.history.stream().map(h -> new StatusChange(h.fromStatus, h.toStatus, h.reasonCode, h.reasonNote, h.changedAt, new Actor(h.actorKind, h.actorUserId))).toList();
		List<ReservationNote> notes = e.notes.stream().map(n -> new ReservationNote(n.id, n.visibility, n.body, n.createdAt, new Actor(n.authorKind, n.authorUserId))).toList();
		var contact = new Contact(e.contactFirstName, e.contactLastName, e.contactDialCode, e.contactPhone, e.contactEmail);
		return new Reservation(e.id, e.reference, e.locale, contact, e.customerId, lines, totals, paid, e.pickupSnapshot, e.createdAt, e.status, e.readyAt, e.expiresAt, history, notes);
	}
}
