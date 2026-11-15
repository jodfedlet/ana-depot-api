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

@Component
class PaymentRepositoryAdapter implements PaymentRepository {

	private final PaymentJpaRepository payments;
	private final LedgerEntryJpaRepository ledger;

	PaymentRepositoryAdapter(PaymentJpaRepository payments, LedgerEntryJpaRepository ledger) {
		this.payments = payments;
		this.ledger = ledger;
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
