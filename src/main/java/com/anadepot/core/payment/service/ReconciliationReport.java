package com.anadepot.core.payment.service;

import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.List;
import java.util.UUID;

/** Compares what the reservation says was paid (cached) with what its ledger adds up to, currency by currency. */
public record ReconciliationReport(UUID reservationId, List<Line> lines) {

	public record Line(SupportedCurrency currency, Money cachedPaid, Money ledgerPaid) {
		public boolean consistent() {
			return cachedPaid.compareTo(ledgerPaid) == 0;
		}
	}

	public boolean consistent() {
		return lines.stream().allMatch(Line::consistent);
	}
}
