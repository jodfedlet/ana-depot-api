package com.anadepot.core.payment.repository;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

	Payment savePayment(Payment payment);

	Optional<Payment> findPayment(UUID id);

	List<Payment> payments(UUID reservationId);

	/** Appends an entry. Ledger entries are never updated or deleted (also enforced by a database trigger). */
	LedgerEntry saveLedgerEntry(LedgerEntry entry);

	List<LedgerEntry> ledger(UUID reservationId);

	Optional<LedgerEntry> findLedgerEntry(UUID id);

	Optional<LedgerEntry> receivedEntryOf(UUID paymentId);

	boolean isReversed(UUID ledgerEntryId);
}
