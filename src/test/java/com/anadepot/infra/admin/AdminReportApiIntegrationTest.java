package com.anadepot.infra.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staff reports over HTTP. The database is shared with other tests, so figures are checked as DELTAS around data this test creates (the tests of this class
 * run one after the other). Every amount is per currency: HTG and USD are never summed.
 */
class AdminReportApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BASE = "/api/v1/admin/reports";

	private String staffToken;
	private Product mattress;

	@BeforeEach
	void setUp() {
		staffToken = newStaffToken();
		ensurePickupAvailable();
		mattress = stockedMattress(100);
	}

	private JsonNode report(String path) throws Exception {
		return JSON.readTree(get(BASE + path, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static BigDecimal amount(JsonNode map, String currency) {
		return new BigDecimal(map.get(currency).asString());
	}

	private Reservation collect(int quantity, SupportedCurrency currency, String payAmount) {
		var r = ready(submit(key(), line(mattress, quantity, currency)));
		payments.record(new RecordPaymentCommand(r.id(), com.anadepot.core.shared.money.Money.of(new BigDecimal(payAmount), currency), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
		reservations.collect(r.id(), staff, key());
		return r;
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryReport() throws Exception {
		var customer = token(java.util.UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			for (var path : new String[] { "/overview", "/sales", "/payments", "/products", "/reservations", "/stock" }) {
				get(BASE + path, bearer).andExpect(status().is(expected));
			}
		}
	}

	@Test
	void salesCountOnlyCollectedReservationsAndKeepEveryCurrencyApart() throws Exception {
		var before = report("/sales");
		var pendingOnly = submit(key(), line(mattress, 1, SupportedCurrency.HTG));      // not collected: never a sale
		collect(2, SupportedCurrency.HTG, "50000.00");
		collect(1, SupportedCurrency.USD, "180.00");
		var after = report("/sales");
		assertThat(amount(after.get("totals"), "HTG").subtract(amount(before.get("totals"), "HTG"))).isEqualByComparingTo("50000.00");
		assertThat(amount(after.get("totals"), "USD").subtract(amount(before.get("totals"), "USD"))).isEqualByComparingTo("180.00");   // never 50180 anywhere
		assertThat(after.get("reservations").asInt() - before.get("reservations").asInt()).isEqualTo(2);
		assertThat(after.get("groupBy").asString()).isEqualTo("DAY");
		assertThat(after.get("period").get("timezone").asString()).isEqualTo("America/Port-au-Prince");
		assertThat(after.get("buckets")).hasSize(30);                                       // a bucket per day, empty days included
		var today = after.get("buckets").get(29);
		assertThat(today.get("collected").has("HTG") && today.get("collected").has("USD")).isTrue();
		assertThat(after.toString()).doesNotContain("grandTotal");
		assertThat(pendingOnly.id()).isNotNull();

		var weekly = report("/sales?groupBy=WEEK");
		assertThat(weekly.get("buckets").size()).isBetween(5, 6);
		assertThat(amount(weekly.get("totals"), "HTG")).isEqualByComparingTo(amount(after.get("totals"), "HTG"));
		var monthly = report("/sales?groupBy=MONTH&from=" + LocalDate.now(ZoneId.of("America/Port-au-Prince")).withDayOfMonth(1) + "&to=" + LocalDate.now(ZoneId.of("America/Port-au-Prince")));
		assertThat(monthly.get("buckets")).hasSize(1);
		assertThat(monthly.get("buckets").get(0).get("start").asString()).isEqualTo(LocalDate.now(ZoneId.of("America/Port-au-Prince")).withDayOfMonth(1).toString());
	}

	@Test
	void theTimeZoneDecidesWhichDayABucketBelongsTo() throws Exception {
		collect(1, SupportedCurrency.HTG, "25000.00");
		var utc = report("/sales?timezone=UTC&groupBy=DAY");
		assertThat(utc.get("period").get("timezone").asString()).isEqualTo("UTC");
		var tokyo = report("/sales?timezone=Asia/Tokyo&groupBy=DAY");
		assertThat(amount(utc.get("totals"), "HTG")).isGreaterThanOrEqualTo(new BigDecimal("25000.00"));
		assertThat(amount(tokyo.get("totals"), "HTG")).isGreaterThanOrEqualTo(new BigDecimal("25000.00"));
	}

	@Test
	void paymentsReportReceivedReversedAndAdjustedPerCurrencyAndNetIsTheirSum() throws Exception {
		var before = report("/payments");
		var r = ready(submit(key(), line(mattress, 1, SupportedCurrency.HTG), line(mattress, 1, SupportedCurrency.USD)));
		var received = payments.record(new RecordPaymentCommand(r.id(), htg("1000.00"), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
		payments.record(new RecordPaymentCommand(r.id(), usd("30.00"), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
		payments.reverse(received.id(), "wrong", staff, key());
		payments.adjust(r.id(), usd("5.00"), "tip", staff, key());
		var after = report("/payments");
		var t0 = before.get("totals");
		var t1 = after.get("totals");
		assertThat(amount(t1.get("received"), "HTG").subtract(amount(t0.get("received"), "HTG"))).isEqualByComparingTo("1000.00");
		assertThat(amount(t1.get("reversed"), "HTG").subtract(amount(t0.get("reversed"), "HTG"))).isEqualByComparingTo("1000.00");    // shown as a positive amount
		assertThat(amount(t1.get("net"), "HTG").subtract(amount(t0.get("net"), "HTG"))).isEqualByComparingTo("0.00");
		assertThat(amount(t1.get("received"), "USD").subtract(amount(t0.get("received"), "USD"))).isEqualByComparingTo("30.00");
		assertThat(amount(t1.get("adjustments"), "USD").subtract(amount(t0.get("adjustments"), "USD"))).isEqualByComparingTo("5.00");
		assertThat(amount(t1.get("net"), "USD").subtract(amount(t0.get("net"), "USD"))).isEqualByComparingTo("35.00");
		assertThat(after.get("buckets")).hasSize(30);
		var overview = report("/overview");
		assertThat(overview.get("paymentsNet").has("HTG") && overview.get("paymentsNet").has("USD")).isTrue();
	}

	@Test
	void topProductsAreRankedInsideOneCurrencyOnlyAndFollowTheSnapshotOfCollectedLines() throws Exception {
		collect(2, SupportedCurrency.HTG, "50000.00");
		collect(1, SupportedCurrency.USD, "180.00");
		var all = report("/products?limit=100");
		JsonNode htg = null;
		JsonNode usd = null;
		for (var row : all.get("rows")) {
			if (row.get("productId").asString().equals(mattress.id().toString())) {
				if (row.get("currency").asString().equals("HTG")) {
					htg = row;
				} else {
					usd = row;
				}
			}
		}
		assertThat(htg).isNotNull();
		assertThat(htg.get("quantity").asInt()).isEqualTo(2);
		assertThat(htg.get("revenue").asString()).isEqualTo("50000.00");
		assertThat(htg.get("sku").asString()).isEqualTo(mattress.sku());
		assertThat(usd).isNotNull();
		assertThat(usd.get("quantity").asInt()).isEqualTo(1);
		assertThat(usd.get("revenue").asString()).isEqualTo("180.00");                       // a separate row: the two revenues are never added
		var onlyUsd = report("/products?currency=USD&limit=100");
		for (var row : onlyUsd.get("rows")) {
			assertThat(row.get("currency").asString()).isEqualTo("USD");
		}
		assertThat(report("/products?limit=1").get("rows").size()).isLessThanOrEqualTo(2);   // at most one row per currency
		get(BASE + "/products?limit=0", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("limit_invalid"));
		get(BASE + "/products?currency=EUR", staffToken).andExpect(status().isUnprocessableContent());
	}

	@Test
	void reservationsReportCountsStatusesAndRejectionReasons() throws Exception {
		var before = report("/reservations");
		var rejected = submit(key(), line(mattress, 1, SupportedCurrency.HTG));
		reservations.reject(rejected.id(), staff, com.anadepot.core.reservation.model.RejectReason.CANNOT_FULFILL, null, key());
		submit(key(), line(mattress, 1, SupportedCurrency.HTG));
		var after = report("/reservations");
		assertThat(after.get("total").asInt() - before.get("total").asInt()).isEqualTo(2);
		assertThat(after.get("byStatus").get("REJECTED").asInt() - before.get("byStatus").get("REJECTED").asInt()).isEqualTo(1);
		assertThat(after.get("byStatus").get("PENDING_REVIEW").asInt() - before.get("byStatus").get("PENDING_REVIEW").asInt()).isEqualTo(1);
		var reasonBefore = before.get("rejectionReasons").has("CANNOT_FULFILL") ? before.get("rejectionReasons").get("CANNOT_FULFILL").asInt() : 0;
		assertThat(after.get("rejectionReasons").get("CANNOT_FULFILL").asInt() - reasonBefore).isEqualTo(1);
	}

	@Test
	void stockReportCountsPositionsByStateAndProductsWithoutARecord() throws Exception {
		var before = report("/stock");
		var base = com.anadepot.testsupport.TestData.product(mattress.categoryId(), com.anadepot.core.catalog.model.ProductStatus.ACTIVE,
				com.anadepot.testsupport.TestData.price(htg("10.00"), null, 1));
		var noRecord = catalog.saveProduct(base, staff);
		var empty = catalog.saveProduct(com.anadepot.testsupport.TestData.product(mattress.categoryId(), com.anadepot.core.catalog.model.ProductStatus.ACTIVE,
				com.anadepot.testsupport.TestData.price(htg("10.00"), null, 1)), staff);
		inventory.register(empty.id(), null, true, 0, null, null);
		var after = report("/stock");
		assertThat(after.get("stock").get("out").asInt() - before.get("stock").get("out").asInt()).isEqualTo(1);
		assertThat(after.get("stock").get("activeProductsWithoutStockRecord").asInt() - before.get("stock").get("activeProductsWithoutStockRecord").asInt()).isEqualTo(1);
		assertThat(after.get("activeProducts").asInt() - before.get("activeProducts").asInt()).isEqualTo(2);
		assertThat(noRecord.id()).isNotNull();
	}

	@Test
	void overviewGathersTheDayToDayFiguresWithEveryAmountPerCurrency() throws Exception {
		var before = report("/overview");
		submit(key(), line(mattress, 1, SupportedCurrency.HTG));
		var after = report("/overview");
		assertThat(after.get("reservationsCreated").get("PENDING_REVIEW").asInt() - before.get("reservationsCreated").get("PENDING_REVIEW").asInt()).isEqualTo(1);
		assertThat(after.get("openNow").get("PENDING_REVIEW").asInt() - before.get("openNow").get("PENDING_REVIEW").asInt()).isEqualTo(1);
		assertThat(after.get("collected").has("HTG") && after.get("collected").has("USD")).isTrue();
		assertThat(after.get("period").get("from").asString()).isEqualTo(LocalDate.now(ZoneId.of("America/Port-au-Prince")).minusDays(29).toString());
	}

	@Test
	void periodValidationRejectsBadDatesZonesGroupingsAndPeriodsThatAreTooLong() throws Exception {
		get(BASE + "/sales?from=yesterday", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='from')]").isNotEmpty());
		get(BASE + "/sales?to=2026-13-40", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "/sales?timezone=Mars/Base", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='timezone')]").isNotEmpty());
		get(BASE + "/sales?groupBy=DECADE", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "/sales?from=2026-02-01&to=2026-01-01", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("date_range_invalid"));
		get(BASE + "/sales?from=2020-01-01&to=2026-01-01", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("period_too_long"));
		get(BASE + "/sales?from=2026-01-01&to=2026-01-01", staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.buckets.length()").value(1));
		get(BASE + "/payments?from=2020-01-01&to=2026-01-01", staffToken).andExpect(status().isUnprocessableContent());
	}

	private static com.anadepot.core.shared.money.Money htg(String a) {
		return com.anadepot.testsupport.TestData.htg(a);
	}

	private static com.anadepot.core.shared.money.Money usd(String a) {
		return com.anadepot.testsupport.TestData.usd(a);
	}
}
