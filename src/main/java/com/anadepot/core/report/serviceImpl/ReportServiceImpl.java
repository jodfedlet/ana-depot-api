package com.anadepot.core.report.serviceImpl;

import com.anadepot.core.report.model.Grouping;
import com.anadepot.core.report.model.ReportPeriod;
import com.anadepot.core.report.model.ReportRows;
import com.anadepot.core.report.repository.ReportRepository;
import com.anadepot.core.report.service.ReportService;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ReportServiceImpl implements ReportService {

	private final ReportRepository reports;
	private final TransactionBoundary tx;

	public ReportServiceImpl(ReportRepository reports, TransactionBoundary tx) {
		this.reports = reports;
		this.tx = tx;
	}

	@Override
	public Overview overview(ReportPeriod p) {
		return tx.inTransaction(() -> {
			var collected = CurrencyTotals.sum(reports.collected(p.start(), p.end()).stream().map(ReportRows.Collected::total).toList());
			var net = CurrencyTotals.sum(reports.ledger(p.start(), p.end()).stream().map(ReportRows.LedgerMovement::amount).toList());
			return new Overview(p, ordered(reports.reservationsCreated(p.start(), p.end())), ordered(reports.openNow()), perCurrency(collected), perCurrency(net), reports.stockCounts(),
					reports.activeProducts(), reports.newCustomers(p.start(), p.end()));
		});
	}

	@Override
	public SalesSeries sales(ReportPeriod p, Grouping grouping) {
		return tx.inTransaction(() -> {
			var rows = reports.collected(p.start(), p.end());
			var perBucket = new TreeMap<LocalDate, CurrencyTotals>();
			for (var bucket : buckets(p, grouping)) {
				perBucket.put(bucket, CurrencyTotals.empty());
			}
			var total = CurrencyTotals.empty();
			for (var row : rows) {
				var bucket = grouping.bucketOf(row.at().atZone(p.zone()).toLocalDate());
				perBucket.merge(bucket, CurrencyTotals.empty().plus(row.total()), (a, b) -> a.plus(b.get(row.total().currency())));
				total = total.plus(row.total());
			}
			var reservationsPerBucket = reservationCounts(rows, p, grouping);
			var list = new ArrayList<SalesBucket>();
			long all = 0;
			for (var e : perBucket.entrySet()) {
				long n = reservationsPerBucket.getOrDefault(e.getKey(), 0L);
				all += n;
				list.add(new SalesBucket(e.getKey(), perCurrency(e.getValue()), n));
			}
			return new SalesSeries(p, grouping, list, perCurrency(total), all);
		});
	}

	/** Collected reservations per bucket: a reservation with two currency totals appears in two rows, so count distinct reservation ids. */
	private static Map<LocalDate, Long> reservationCounts(List<ReportRows.Collected> rows, ReportPeriod p, Grouping grouping) {
		var seen = new TreeMap<LocalDate, java.util.Set<java.util.UUID>>();
		for (var row : rows) {
			seen.computeIfAbsent(grouping.bucketOf(row.at().atZone(p.zone()).toLocalDate()), k -> new java.util.HashSet<>()).add(row.reservationId());
		}
		var result = new TreeMap<LocalDate, Long>();
		seen.forEach((k, v) -> result.put(k, (long) v.size()));
		return result;
	}

	@Override
	public PaymentsSeries payments(ReportPeriod p, Grouping grouping) {
		return tx.inTransaction(() -> {
			var rows = reports.ledger(p.start(), p.end());
			var received = new TreeMap<LocalDate, CurrencyTotals>();
			var reversed = new TreeMap<LocalDate, CurrencyTotals>();
			var adjustments = new TreeMap<LocalDate, CurrencyTotals>();
			for (var bucket : buckets(p, grouping)) {
				received.put(bucket, CurrencyTotals.empty());
				reversed.put(bucket, CurrencyTotals.empty());
				adjustments.put(bucket, CurrencyTotals.empty());
			}
			var tr = CurrencyTotals.empty();
			var tv = CurrencyTotals.empty();
			var ta = CurrencyTotals.empty();
			for (var row : rows) {
				var bucket = grouping.bucketOf(row.at().atZone(p.zone()).toLocalDate());
				switch (row.type()) {
					case PAYMENT_RECEIVED -> {
						received.merge(bucket, CurrencyTotals.empty().plus(row.amount()), (a, b) -> a.plus(row.amount()));
						tr = tr.plus(row.amount());
					}
					case PAYMENT_REVERSAL -> {
						reversed.merge(bucket, CurrencyTotals.empty().plus(row.amount()), (a, b) -> a.plus(row.amount()));
						tv = tv.plus(row.amount());
					}
					case ADJUSTMENT -> {
						adjustments.merge(bucket, CurrencyTotals.empty().plus(row.amount()), (a, b) -> a.plus(row.amount()));
						ta = ta.plus(row.amount());
					}
				}
			}
			var list = new ArrayList<PaymentBucket>();
			for (var bucket : received.keySet()) {
				list.add(bucket(bucket, received.get(bucket), reversed.get(bucket), adjustments.get(bucket)));
			}
			return new PaymentsSeries(p, grouping, list, bucket(null, tr, tv, ta));
		});
	}

	private static PaymentBucket bucket(LocalDate start, CurrencyTotals received, CurrencyTotals reversed, CurrencyTotals adjustments) {
		var net = CurrencyTotals.empty();
		for (var totals : List.of(received, reversed, adjustments)) {
			for (var m : totals.amounts()) {
				net = net.plus(m);
			}
		}
		// reversals are stored negative: show them as a positive "reversed" amount, as a staff member reads them
		var reversedShown = CurrencyTotals.empty();
		for (var m : reversed.amounts()) {
			reversedShown = reversedShown.plus(m.negate());
		}
		return new PaymentBucket(start, perCurrency(received), perCurrency(reversedShown), perCurrency(adjustments), perCurrency(net));
	}

	@Override
	public List<ReportRows.ProductSales> topProducts(ReportPeriod p, SupportedCurrency currencyOrNull, int limit) {
		if (limit < 1 || limit > 100) {
			throw new DomainRuleViolation("limit_invalid", "limit must be between 1 and 100");
		}
		return tx.inTransaction(() -> {
			var rows = reports.productSales(p.start(), p.end()).stream().filter(r -> currencyOrNull == null || r.currency() == currencyOrNull).toList();
			var result = new ArrayList<ReportRows.ProductSales>();
			for (var currency : SupportedCurrency.values()) {   // ranked inside one currency only: revenue of different currencies is never compared
				rows.stream().filter(r -> r.currency() == currency)
						.sorted(Comparator.comparing((ReportRows.ProductSales r) -> r.revenue().amount()).reversed().thenComparing(ReportRows.ProductSales::quantity, Comparator.reverseOrder())
								.thenComparing(ReportRows.ProductSales::productId))
						.limit(limit).forEach(result::add);
			}
			return result;
		});
	}

	@Override
	public ReservationsReport reservations(ReportPeriod p) {
		return tx.inTransaction(() -> {
			var byStatus = ordered(reports.reservationsCreated(p.start(), p.end()));
			long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
			var reasons = new LinkedHashMap<String, Long>();
			reports.rejectionReasons(p.start(), p.end()).entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
					.forEach(e -> reasons.put(e.getKey(), e.getValue()));
			return new ReservationsReport(p, byStatus, total, reasons);
		});
	}

	@Override
	public StockReport stock() {
		return tx.inTransaction(() -> new StockReport(reports.stockCounts(), reports.activeProducts()));
	}

	// ---- helpers ----

	private static List<LocalDate> buckets(ReportPeriod p, Grouping grouping) {
		var result = new ArrayList<LocalDate>();
		for (var b = grouping.bucketOf(p.from()); !b.isAfter(p.to()); b = grouping.next(b)) {
			result.add(b);
		}
		return result;
	}

	/** Every currency of the platform, zero when absent, so a screen can always show both columns. */
	private static Map<SupportedCurrency, Money> perCurrency(CurrencyTotals totals) {
		var map = new EnumMap<SupportedCurrency, Money>(SupportedCurrency.class);
		for (var currency : SupportedCurrency.values()) {
			map.put(currency, totals.get(currency));
		}
		return map;
	}

	private static Map<ReservationStatus, Long> ordered(Map<ReservationStatus, Long> counts) {
		var map = new EnumMap<ReservationStatus, Long>(ReservationStatus.class);
		for (var status : ReservationStatus.values()) {
			map.put(status, counts.getOrDefault(status, 0L));
		}
		return map;
	}
}
