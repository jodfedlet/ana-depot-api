package com.anadepot.infra.report;

import com.anadepot.core.report.model.Grouping;
import com.anadepot.core.report.model.ReportPeriod;
import com.anadepot.core.report.model.ReportRows;
import com.anadepot.core.report.service.ReportService;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.web.QueryParams;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff reports ({@code /api/v1/admin/reports}). Periods are whole days {@code from..to} (both inclusive, {@code yyyy-MM-dd}) in the store's time zone
 * ({@code timezone}, default America/Port-au-Prince); the default period is the last 30 days. EVERY amount is per currency, as decimal text: HTG and USD are
 * side by side and never summed or converted. Sales are COLLECTED reservations dated by collection; payments are ledger entries dated by their entry.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
class AdminReportController {

	static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Port-au-Prince");

	record Period(LocalDate from, LocalDate to, String timezone) {
	}

	record Stock(long ok, long low, long out, long untracked, long activeProductsWithoutStockRecord) {
	}

	record OverviewResponse(Period period, Map<String, Long> reservationsCreated, Map<String, Long> openNow, Map<String, String> collected, Map<String, String> paymentsNet, Stock stock,
			long activeProducts, long newCustomers) {
	}

	record SalesBucket(LocalDate start, Map<String, String> collected, long reservations) {
	}

	record SalesResponse(Period period, String groupBy, List<SalesBucket> buckets, Map<String, String> totals, long reservations) {
	}

	record PaymentBucket(LocalDate start, Map<String, String> received, Map<String, String> reversed, Map<String, String> adjustments, Map<String, String> net) {
	}

	record PaymentsResponse(Period period, String groupBy, List<PaymentBucket> buckets, PaymentBucket totals) {
	}

	record ProductRow(UUID productId, String name, String sku, String currency, long quantity, String revenue, long reservations) {
	}

	record ProductsResponse(Period period, List<ProductRow> rows) {
	}

	record ReservationsResponse(Period period, Map<String, Long> byStatus, long total, Map<String, Long> rejectionReasons) {
	}

	record StockResponse(Stock stock, long activeProducts) {
	}

	private final ReportService reports;

	AdminReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/overview")
	OverviewResponse overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String timezone) {
		var period = period(from, to, timezone);
		var o = reports.overview(period);
		return new OverviewResponse(period(o.period()), statusMap(o.reservationsCreated()), statusMap(o.openNow()), money(o.collected()), money(o.paymentsNet()), stock(o.stock()), o.activeProducts(),
				o.newCustomers());
	}

	@GetMapping("/sales")
	SalesResponse sales(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String timezone,
			@RequestParam(required = false) String groupBy) {
		var grouping = grouping(groupBy);
		var s = reports.sales(period(from, to, timezone), grouping);
		return new SalesResponse(period(s.period()), grouping.name(), s.buckets().stream().map(b -> new SalesBucket(b.start(), money(b.collected()), b.reservations())).toList(), money(s.totals()),
				s.reservations());
	}

	@GetMapping("/payments")
	PaymentsResponse payments(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String timezone,
			@RequestParam(required = false) String groupBy) {
		var grouping = grouping(groupBy);
		var p = reports.payments(period(from, to, timezone), grouping);
		return new PaymentsResponse(period(p.period()), grouping.name(), p.buckets().stream().map(AdminReportController::paymentBucket).toList(), paymentBucket(p.totals()));
	}

	@GetMapping("/products")
	ProductsResponse products(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String timezone,
			@RequestParam(required = false) String currency, @RequestParam(defaultValue = "10") int limit) {
		var period = period(from, to, timezone);
		var rows = reports.topProducts(period, QueryParams.enumOf(SupportedCurrency.class, currency, "currency"), limit);
		return new ProductsResponse(period(period), rows.stream().map(AdminReportController::productRow).toList());
	}

	@GetMapping("/reservations")
	ReservationsResponse reservations(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String timezone) {
		var r = reports.reservations(period(from, to, timezone));
		return new ReservationsResponse(period(r.period()), statusMap(r.byStatus()), r.total(), r.rejectionReasons());
	}

	@GetMapping("/stock")
	StockResponse stock() {
		var s = reports.stock();
		return new StockResponse(stock(s.counts()), s.activeProducts());
	}

	// ---- parsing and mapping ----

	private static ReportPeriod period(String from, String to, String timezone) {
		var zone = zone(timezone);
		var today = LocalDate.now(zone);
		var end = date(to, "to", today);
		var start = date(from, "from", end.minusDays(29));
		return new ReportPeriod(start, end, zone);
	}

	private static LocalDate date(String text, String field, LocalDate fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(text.strip());
		} catch (DateTimeParseException e) {
			throw new FieldValidationException(List.of(new FieldIssue(field, "invalid")));
		}
	}

	private static ZoneId zone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return DEFAULT_ZONE;
		}
		try {
			return ZoneId.of(timezone.strip());
		} catch (DateTimeException e) {
			throw new FieldValidationException(List.of(new FieldIssue("timezone", "invalid")));
		}
	}

	private static Grouping grouping(String groupBy) {
		var g = QueryParams.enumOf(Grouping.class, groupBy, "groupBy");
		return g == null ? Grouping.DAY : g;
	}

	private static Period period(ReportPeriod p) {
		return new Period(p.from(), p.to(), p.zone().getId());
	}

	private static Map<String, String> money(Map<SupportedCurrency, Money> amounts) {
		var out = new LinkedHashMap<String, String>();
		amounts.forEach((c, m) -> out.put(c.name(), m.amount().toPlainString()));
		return out;
	}

	private static Map<String, Long> statusMap(Map<ReservationStatus, Long> counts) {
		var out = new LinkedHashMap<String, Long>();
		counts.forEach((s, n) -> out.put(s.name(), n));
		return out;
	}

	private static Stock stock(ReportRows.StockCounts s) {
		return new Stock(s.ok(), s.low(), s.out(), s.untracked(), s.productsWithoutStockRecord());
	}

	private static PaymentBucket paymentBucket(ReportService.PaymentBucket b) {
		return new PaymentBucket(b.start(), money(b.received()), money(b.reversed()), money(b.adjustments()), money(b.net()));
	}

	private static ProductRow productRow(ReportRows.ProductSales r) {
		return new ProductRow(r.productId(), r.name(), r.sku(), r.currency().name(), r.quantity(), r.revenue().amount().toPlainString(), r.reservations());
	}
}
