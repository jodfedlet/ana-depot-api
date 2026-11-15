package com.anadepot.core.payment.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable line of a reservation's payment ledger. The signed {@code amount} (in its own currency) is what
 * changes what the customer has paid: +received, -reversal, +/-adjustment. History is never rewritten: a correction
 * is a NEW entry that points at what it corrects ({@code reversesEntryId}). A reservation's paid amount in a currency is
 * the SUM of its entries; the number stored on the reservation is only a cache and can be reconciled against the ledger.
 *
 * <p>This is a single-sided payments journal, not an accounting ledger: no accounts, no debits/credits. When accounting
 * postings are introduced they must be balanced double-entry journal entries (see docs/adr/0001).
 */
public record LedgerEntry(UUID id, UUID reservationId, UUID paymentId, LedgerEntryType type, Money amount,
		UUID reversesEntryId, String reason, Instant at, UUID createdBy, String correlationId) {

	public LedgerEntry {
		if (amount.isZero()) {
			throw new DomainRuleViolation("ledger_amount_zero", "A ledger entry cannot be zero");
		}
		switch (type) {
			case PAYMENT_RECEIVED -> {
				if (!amount.isPositive() || paymentId == null) {
					throw new DomainRuleViolation("ledger_entry_invalid", "A received payment is positive and references its payment");
				}
			}
			case PAYMENT_REVERSAL -> {
				if (!amount.isNegative() || reversesEntryId == null || paymentId == null) {
					throw new DomainRuleViolation("ledger_entry_invalid", "A reversal is negative and references the entry and payment it reverses");
				}
			}
			case ADJUSTMENT -> {
				if (reason == null || reason.isBlank()) {
					throw new DomainRuleViolation("reason_required", "An adjustment needs a reason");
				}
			}
		}
	}
}
