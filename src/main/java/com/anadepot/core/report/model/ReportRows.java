package com.anadepot.core.report.model;

import com.anadepot.core.payment.model.LedgerEntryType;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.UUID;

/** The raw facts the report service aggregates. Money stays in its own currency: nothing here is ever added across currencies. */
public final class ReportRows {

	private ReportRows() {
	}

	/** One currency total of a reservation that was collected at {@code at}. */
	public record Collected(UUID reservationId, Instant at, Money total) {
	}

	/** One ledger entry: +received, -reversal, +/-adjustment, in its own currency. */
	public record LedgerMovement(Instant at, LedgerEntryType type, Money amount) {
	}

	/** What one product sold (collected reservations only) in one currency. */
	public record ProductSales(UUID productId, String name, String sku, SupportedCurrency currency, long quantity, Money revenue, long reservations) {
	}

	/** Stock positions by state, plus the products that cannot be reserved yet because they have no stock record. */
	public record StockCounts(long ok, long low, long out, long untracked, long productsWithoutStockRecord) {
	}
}
