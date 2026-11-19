package com.anadepot.core.payment.service;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.PaymentQuery;
import com.anadepot.core.payment.model.PaymentView;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.UUID;

/** Staff reading of payments and ledgers. Writes (record, reverse, adjust) are {@link PaymentService}: transactional, audited, idempotent. */
public interface PaymentAdminService {

	PageResult<PaymentView> list(PaymentQuery query, PageRequest page);

	PaymentView get(UUID paymentId);

	/** Every payment of one reservation, oldest first. */
	List<PaymentView> forReservation(UUID reservationId);

	/**
	 * D1 (owner-approved): money received by CARD that the store must give back because the reservation ended without collection (rejected, cancelled
	 * or expired). Per currency, never converted; empty for every other situation. The refund itself is done by the owner in the Stripe dashboard.
	 */
	List<com.anadepot.core.shared.money.Money> refundDue(com.anadepot.core.reservation.model.Reservation reservation);

	/** The full ledger of one reservation, oldest first (payments, reversals, adjustments). */
	List<LedgerEntry> ledger(UUID reservationId);
}
