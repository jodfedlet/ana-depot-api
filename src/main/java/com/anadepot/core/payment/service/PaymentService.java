package com.anadepot.core.payment.service;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.Payment;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.money.Money;
import java.util.UUID;

/**
 * Payments at pickup. A payment settles ONLY the balance of its own currency; the reservation is PAID when every
 * currency is settled. Every write needs an idempotency key. Financial corrections are new ledger entries (reversal,
 * adjustment), never edits. Who may call these is an authorization concern (roles are undecided) and not decided here.
 */
public interface PaymentService {

	Payment record(RecordPaymentCommand command);

	/** Cancels a recorded payment with a compensating ledger entry. Allowed only while the reservation is READY_FOR_PICKUP (conservative; policy undecided). */
	Payment reverse(UUID paymentId, String reason, Actor actor, String idempotencyKey);

	/** Signed correction of what was paid in one currency; reason mandatory. READY_FOR_PICKUP only. */
	LedgerEntry adjust(UUID reservationId, Money signedAmount, String reason, Actor actor, String idempotencyKey);

	ReconciliationReport reconcile(UUID reservationId);
}
