package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.LedgerEntryType;
import com.anadepot.core.payment.model.Payment;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.shared.money.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class PaymentRepositoryAdapter implements PaymentRepository {

	private final PaymentJpaRepository payments;
	private final LedgerEntryJpaRepository ledger;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager em;

	PaymentRepositoryAdapter(PaymentJpaRepository payments, LedgerEntryJpaRepository ledger) {
		this.payments = payments;
		this.ledger = ledger;
	}

	@Override
	public com.anadepot.core.shared.PageResult<Payment> findPage(com.anadepot.core.payment.model.PaymentQuery q, com.anadepot.core.shared.PageRequest page) {
		var from = new StringBuilder(" from PaymentEntity p where 1 = 1");
		var params = new java.util.LinkedHashMap<String, Object>();
		if (q.reservationId() != null) {
			from.append(" and p.reservationId = :reservationId");
			params.put("reservationId", q.reservationId());
		}
		if (q.currency() != null) {
			from.append(" and p.currency = :currency");
			params.put("currency", q.currency());
		}
		if (q.method() != null) {
			from.append(" and p.paymentMethod = :method");
			params.put("method", q.method());
		}
		if (q.receivedFrom() != null) {
			from.append(" and p.receivedAt >= :from");
			params.put("from", q.receivedFrom());
		}
		if (q.receivedTo() != null) {
			from.append(" and p.receivedAt < :to");
			params.put("to", q.receivedTo());
		}
		if (q.recordedBy() != null) {
			from.append(" and p.recordedBy = :recordedBy");
			params.put("recordedBy", q.recordedBy());
		}
		if (q.reversed() != null) {
			from.append(q.reversed() ? " and " : " and not ").append("exists (select 1 from LedgerEntryEntity l where l.paymentId = p.id and l.entryType = :reversalType)");
			params.put("reversalType", LedgerEntryType.PAYMENT_REVERSAL);
		}
		var query = em.createQuery("select p" + from + " order by p.receivedAt desc, p.id", PaymentEntity.class);
		var count = em.createQuery("select count(p)" + from, Long.class);
		params.forEach((k, v) -> {
			query.setParameter(k, v);
			count.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		return new com.anadepot.core.shared.PageResult<>(query.getResultList().stream().map(PaymentRepositoryAdapter::toDomain).toList(), page.page(), page.size(), count.getSingleResult());
	}

	@Override
	public java.util.Map<UUID, LedgerEntry> reversalsOf(java.util.Collection<UUID> paymentIds) {
		var result = new java.util.HashMap<UUID, LedgerEntry>();
		if (!paymentIds.isEmpty()) {
			em.createQuery("select l from LedgerEntryEntity l where l.entryType = :type and l.paymentId in :ids", LedgerEntryEntity.class).setParameter("type", LedgerEntryType.PAYMENT_REVERSAL)
					.setParameter("ids", paymentIds).getResultList().forEach(e -> result.put(e.paymentId, toDomain(e)));
		}
		return result;
	}

	@Override
	public Payment savePayment(Payment p) {
		var e = new PaymentEntity();
		e.id = p.id();
		e.reservationId = p.reservationId();
		e.currency = p.amount().currency();
		e.amount = p.amount().amount();
		e.paymentMethod = p.method();
		e.receivedAt = p.receivedAt();
		e.recordedAt = p.recordedAt();
		e.recordedBy = p.recordedBy();
		e.reference = p.reference();
		e.note = p.note();
		payments.saveAndFlush(e);
		return p;
	}

	@Override
	public Optional<Payment> findPayment(UUID id) {
		return payments.findById(id).map(PaymentRepositoryAdapter::toDomain);
	}

	@Override
	public List<Payment> payments(UUID reservationId) {
		return payments.findByReservationIdOrderByRecordedAtAscIdAsc(reservationId).stream().map(PaymentRepositoryAdapter::toDomain).toList();
	}

	@Override
	public LedgerEntry saveLedgerEntry(LedgerEntry l) {
		var e = new LedgerEntryEntity();
		e.id = l.id();
		e.reservationId = l.reservationId();
		e.paymentId = l.paymentId();
		e.entryType = l.type();
		e.currency = l.amount().currency();
		e.amount = l.amount().amount();
		e.reversesEntryId = l.reversesEntryId();
		e.reason = l.reason();
		e.createdAt = l.at();
		e.createdBy = l.createdBy();
		e.correlationId = l.correlationId();
		ledger.saveAndFlush(e);
		return l;
	}

	@Override
	public List<LedgerEntry> ledger(UUID reservationId) {
		return ledger.findByReservationIdOrderByCreatedAtAscIdAsc(reservationId).stream().map(PaymentRepositoryAdapter::toDomain).toList();
	}

	@Override
	public Optional<LedgerEntry> findLedgerEntry(UUID id) {
		return ledger.findById(id).map(PaymentRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<LedgerEntry> receivedEntryOf(UUID paymentId) {
		return ledger.findByPaymentIdAndEntryType(paymentId, LedgerEntryType.PAYMENT_RECEIVED).map(PaymentRepositoryAdapter::toDomain);
	}

	@Override
	public boolean isReversed(UUID ledgerEntryId) {
		return ledger.existsByReversesEntryId(ledgerEntryId);
	}

	private static Payment toDomain(PaymentEntity e) {
		return new Payment(e.id, e.reservationId, Money.of(e.amount, e.currency), e.paymentMethod, e.receivedAt, e.recordedAt, e.recordedBy, e.reference, e.note);
	}

	private static LedgerEntry toDomain(LedgerEntryEntity e) {
		return new LedgerEntry(e.id, e.reservationId, e.paymentId, e.entryType, Money.of(e.amount, e.currency), e.reversesEntryId, e.reason, e.createdAt, e.createdBy, e.correlationId);
	}
}
