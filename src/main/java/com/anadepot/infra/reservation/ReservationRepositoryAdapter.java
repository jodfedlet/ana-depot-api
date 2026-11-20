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
import com.anadepot.core.shared.money.SupportedCurrency;
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
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager em;

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
		e.termsVersion = r.termsVersion();
		e.termsAcceptedAt = r.termsAcceptedAt();
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
	public PageResult<Reservation> findPage(com.anadepot.core.reservation.model.ReservationQuery q, PageRequest page) {
		var from = new StringBuilder(" from ReservationEntity r where 1 = 1");
		var params = new java.util.LinkedHashMap<String, Object>();
		if (q.status() != null) {
			from.append(" and r.status = :status");
			params.put("status", q.status());
		}
		if (q.currency() != null) {
			from.append(" and exists (select 1 from ReservationTotalEntity c where c.reservation = r and c.currency = :currency)");
			params.put("currency", q.currency());
		}
		if (q.paymentStatus() != null) {
			final String unsettled = "exists (select 1 from ReservationTotalEntity t where t.reservation = r and t.paidAmount < t.totalAmount)";
			final String anyPaid = "exists (select 1 from ReservationTotalEntity t where t.reservation = r and t.paidAmount > 0)";
			switch (q.paymentStatus()) {
				case PAID -> from.append(" and not ").append(unsettled);
				case UNPAID -> from.append(" and not ").append(anyPaid);
				case PARTIAL -> from.append(" and ").append(anyPaid).append(" and ").append(unsettled);
			}
		}
		if (q.productId() != null) {
			from.append(" and exists (select 1 from ReservationLineEntity l where l.reservation = r and l.productId = :productId)");
			params.put("productId", q.productId());
		}
		if (q.customerId() != null) {
			from.append(" and r.customerId = :customerId");
			params.put("customerId", q.customerId());
		}
		if (q.createdFrom() != null) {
			from.append(" and r.createdAt >= :from");
			params.put("from", q.createdFrom());
		}
		if (q.createdTo() != null) {
			from.append(" and r.createdAt < :to");
			params.put("to", q.createdTo());
		}
		if (q.text() != null) {
			var text = escape(q.text().toLowerCase(java.util.Locale.ROOT));
			var digits = q.text().replaceAll("\\D", "");
			from.append(" and (r.reference like :reference escape '\\' or lower(r.contactFirstName) like :name escape '\\' or lower(r.contactLastName) like :name escape '\\'")
					.append(" or lower(concat(r.contactFirstName, ' ', r.contactLastName)) like :name escape '\\' or lower(r.contactEmail) like :name escape '\\'");
			params.put("reference", escape(q.text().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "")) + "%");
			params.put("name", "%" + text + "%");
			if (digits.length() >= 4) {
				from.append(" or replace(replace(replace(r.contactPhone, ' ', ''), '-', ''), '+', '') like :phone escape '\\'");
				params.put("phone", "%" + digits + "%");
			}
			from.append(")");
		}
		var order = q.sort() == com.anadepot.core.reservation.model.ReservationQuery.Sort.OLDEST ? " order by r.createdAt asc, r.id" : " order by r.createdAt desc, r.id";
		var query = em.createQuery("select r" + from + order, ReservationEntity.class);
		var count = em.createQuery("select count(r)" + from, Long.class);
		params.forEach((k, v) -> {
			query.setParameter(k, v);
			count.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		return new PageResult<>(query.getResultList().stream().map(ReservationRepositoryAdapter::toDomain).toList(), page.page(), page.size(), count.getSingleResult());
	}

	@Override
	public java.util.Map<UUID, String> referencesOf(java.util.Collection<UUID> ids) {
		var result = new java.util.HashMap<UUID, String>();
		if (!ids.isEmpty()) {
			em.createQuery("select r.id, r.reference from ReservationEntity r where r.id in :ids", Object[].class).setParameter("ids", ids).getResultList()
					.forEach(row -> result.put((UUID) row[0], (String) row[1]));
		}
		return result;
	}

	@Override
	public java.util.Map<UUID, com.anadepot.core.customer.model.CustomerActivity> activityOf(java.util.Collection<UUID> customerIds) {
		var result = new java.util.HashMap<UUID, com.anadepot.core.customer.model.CustomerActivity>();
		if (!customerIds.isEmpty()) {
			em.createQuery("select r.customerId, count(r), min(r.createdAt), max(r.createdAt) from ReservationEntity r where r.customerId in :ids group by r.customerId", Object[].class)
					.setParameter("ids", customerIds).getResultList()
					.forEach(row -> result.put((UUID) row[0], new com.anadepot.core.customer.model.CustomerActivity((Long) row[1], (java.time.Instant) row[2], (java.time.Instant) row[3])));
		}
		return result;
	}

	@Override
	public com.anadepot.core.customer.model.CustomerSummary summaryOf(UUID customerId) {
		var byStatus = new java.util.EnumMap<com.anadepot.core.reservation.model.ReservationStatus, Long>(com.anadepot.core.reservation.model.ReservationStatus.class);
		em.createQuery("select r.status, count(r) from ReservationEntity r where r.customerId = :id group by r.status", Object[].class).setParameter("id", customerId).getResultList()
				.forEach(row -> byStatus.put((com.anadepot.core.reservation.model.ReservationStatus) row[0], (Long) row[1]));
		var collected = new java.util.EnumMap<SupportedCurrency, Money>(SupportedCurrency.class);
		em.createQuery("select t.currency, sum(t.totalAmount) from ReservationTotalEntity t where t.reservation.customerId = :id and t.reservation.status = :status group by t.currency", Object[].class)
				.setParameter("id", customerId).setParameter("status", com.anadepot.core.reservation.model.ReservationStatus.COLLECTED).getResultList()
				.forEach(row -> collected.put((SupportedCurrency) row[0], Money.of((java.math.BigDecimal) row[1], (SupportedCurrency) row[0])));
		var open = java.util.List.of(com.anadepot.core.reservation.model.ReservationStatus.PENDING_REVIEW, com.anadepot.core.reservation.model.ReservationStatus.CONFIRMED,
				com.anadepot.core.reservation.model.ReservationStatus.READY_FOR_PICKUP);
		var due = new java.util.EnumMap<SupportedCurrency, Money>(SupportedCurrency.class);
		em.createQuery("select t.currency, sum(case when t.totalAmount > t.paidAmount then t.totalAmount - t.paidAmount else 0 end) from ReservationTotalEntity t "
				+ "where t.reservation.customerId = :id and t.reservation.status in :open group by t.currency", Object[].class).setParameter("id", customerId).setParameter("open", open).getResultList()
				.forEach(row -> due.put((SupportedCurrency) row[0], Money.of((java.math.BigDecimal) row[1], (SupportedCurrency) row[0])));
		return new com.anadepot.core.customer.model.CustomerSummary(byStatus, collected, due);
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
		var restored = new Reservation(e.id, e.reference, e.locale, contact, e.customerId, lines, totals, paid, e.pickupSnapshot, e.createdAt, e.status, e.readyAt, e.expiresAt, history, notes);
		restored.restoreTerms(e.termsVersion, e.termsAcceptedAt);
		return restored;
	}

	@Override
	public java.util.List<java.util.UUID> findReadyPastDeadline(java.time.Instant now, int limit) {
		return jpa.readyPastDeadline(now, org.springframework.data.domain.PageRequest.of(0, limit));
	}

	@Override
	public java.util.List<java.util.UUID> findReadyNeedingReminder(java.time.Instant now, java.time.Instant cutoff, int limit) {
		return jpa.readyNeedingReminder(now, cutoff, org.springframework.data.domain.PageRequest.of(0, limit));
	}
}
