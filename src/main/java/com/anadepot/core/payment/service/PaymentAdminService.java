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

	/** The full ledger of one reservation, oldest first (payments, reversals, adjustments). */
	List<LedgerEntry> ledger(UUID reservationId);
}
