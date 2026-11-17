package com.anadepot.core.report.service;

import com.anadepot.core.report.model.Grouping;
import com.anadepot.core.report.model.ReportPeriod;
import com.anadepot.core.report.model.ReportRows;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Staff reports. EVERY money figure is per currency: HTG and USD are reported side by side and never summed, converted or compared. "Sales" are reservations
 * that were COLLECTED (goods handed over), dated by the collection; "payments" are ledger entries dated by their entry. Read-only.
 */
public interface ReportService {

	Overview overview(ReportPeriod period);

	SalesSeries sales(ReportPeriod period, Grouping grouping);

	PaymentsSeries payments(ReportPeriod period, Grouping grouping);

	/** Best sellers: per product and currency, ordered by revenue (within each currency) then units. {@code limit} rows per currency. */
	List<ReportRows.ProductSales> topProducts(ReportPeriod period, SupportedCurrency currencyOrNull, int limit);

	ReservationsReport reservations(ReportPeriod period);

	/** Stock health right now. */
	StockReport stock();

	record Overview(ReportPeriod period, Map<ReservationStatus, Long> reservationsCreated, Map<ReservationStatus, Long> openNow, Map<SupportedCurrency, Money> collected,
			Map<SupportedCurrency, Money> paymentsNet, ReportRows.StockCounts stock, long activeProducts, long newCustomers) {
	}

	record SalesBucket(LocalDate start, Map<SupportedCurrency, Money> collected, long reservations) {
	}

	record SalesSeries(ReportPeriod period, Grouping grouping, List<SalesBucket> buckets, Map<SupportedCurrency, Money> totals, long reservations) {
	}

	record PaymentBucket(LocalDate start, Map<SupportedCurrency, Money> received, Map<SupportedCurrency, Money> reversed, Map<SupportedCurrency, Money> adjustments,
			Map<SupportedCurrency, Money> net) {
	}

	record PaymentsSeries(ReportPeriod period, Grouping grouping, List<PaymentBucket> buckets, PaymentBucket totals) {
	}

	record ReservationsReport(ReportPeriod period, Map<ReservationStatus, Long> byStatus, long total, Map<String, Long> rejectionReasons) {
	}

	record StockReport(ReportRows.StockCounts counts, long activeProducts) {
	}
}
