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
	 * D1 (owner-approved): card money the store must give back because the reservation ended without collection (rejected, cancelled or expired), per currency, never converted.
	 * Refunds are done by the owner in the provider's dashboard; the ones the provider reported (verified events) are subtracted, so the figure clears by itself. Empty lists for every
	 * other situation.
	 */
	RefundPosition refundDue(com.anadepot.core.reservation.model.Reservation reservation);

	/** {@code due}: still to be given back. {@code observedRefunded}: already refunded in the provider's dashboard, as the provider reported it. */
	record RefundPosition(List<com.anadepot.core.shared.money.Money> due, List<com.anadepot.core.shared.money.Money> observedRefunded) {
		public static final RefundPosition NONE = new RefundPosition(List.of(), List.of());
	}

	/** The full ledger of one reservation, oldest first (payments, reversals, adjustments). */
	List<LedgerEntry> ledger(UUID reservationId);
}
