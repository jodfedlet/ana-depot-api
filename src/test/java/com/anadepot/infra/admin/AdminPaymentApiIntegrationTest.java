package com.anadepot.infra.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff payments over HTTP: per-currency settlement, append-only ledger, idempotency, concurrency. Real security chain and PostgreSQL. */
class AdminPaymentApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String ADMIN = "/api/v1/admin";
	@Autowired AuditEventJpaRepository audits;

	private String staffToken;
	private Product mattress;

	@BeforeEach
	void setUp() {
		staffToken = newStaffToken();
		ensurePickupAvailable();
		mattress = stockedMattress(50);
	}

	/** A READY reservation of one mattress in each currency: 25000.00 HTG and 180.00 USD, two independent balances. */
	private Reservation readyBothCurrencies() {
		var r = submit(key(), line(mattress, 1, SupportedCurrency.HTG), line(mattress, 1, SupportedCurrency.USD));
		return ready(r);
	}

	private JsonNode pay(Reservation r, String currency, String amount, String key) throws Exception {
		return JSON.readTree(post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", amount, "currency", currency), staffToken, key).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString());
	}

	private int payStatus(Reservation r, Map<String, Object> body, String key) throws Exception {
		return post(ADMIN + "/reservations/" + r.id() + "/payments", body, staffToken, key).andReturn().getResponse().getStatus();
	}

	private JsonNode balance(JsonNode balances, String currency) {
		for (var b : balances) {
			if (b.get("currency").asString().equals(currency)) {
				return b;
			}
		}
		throw new AssertionError("no " + currency + " balance in " + balances);
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryPaymentEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get(ADMIN + "/payments", bearer).andExpect(status().is(expected));
			get(ADMIN + "/payments/" + id, bearer).andExpect(status().is(expected));
			post(ADMIN + "/payments/" + id + "/reversal", Map.of("reason", "x"), bearer, key()).andExpect(status().is(expected));
			get(ADMIN + "/reservations/" + id + "/payments", bearer).andExpect(status().is(expected));
			post(ADMIN + "/reservations/" + id + "/payments", Map.of("amount", "1.00", "currency", "HTG"), bearer, key()).andExpect(status().is(expected));
			get(ADMIN + "/reservations/" + id + "/ledger", bearer).andExpect(status().is(expected));
			post(ADMIN + "/reservations/" + id + "/payment-adjustments", Map.of("amount", "1.00", "currency", "HTG", "reason", "x"), bearer, key()).andExpect(status().is(expected));
			get(ADMIN + "/reservations/" + id + "/reconciliation", bearer).andExpect(status().is(expected));
		}
	}

	@Test
	void eachCurrencyIsSettledOnItsOwnAndTheReservationIsPaidOnlyWhenEveryCurrencyIs() throws Exception {
		var r = readyBothCurrencies();
		var first = pay(r, "HTG", "25000.00", key());
		assertThat(first.get("paymentStatus").asString()).isEqualTo("PARTIAL");                // HTG settled, USD untouched
		assertThat(balance(first.get("balances"), "HTG").get("due").asString()).isEqualTo("0.00");
		assertThat(balance(first.get("balances"), "USD").get("due").asString()).isEqualTo("180.00");
		assertThat(first.get("payment").get("currency").asString()).isEqualTo("HTG");
		post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", "10.00", "currency", "HTG"), staffToken, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_paid"));
		post("/api/v1/admin/reservations/" + r.id() + "/collect", Map.of(), staffToken, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("payment_required"));
		var second = pay(r, "USD", "180.00", key());
		assertThat(second.get("paymentStatus").asString()).isEqualTo("PAID");
		post("/api/v1/admin/reservations/" + r.id() + "/collect", Map.of(), staffToken, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COLLECTED"));
		var summary = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/payments", staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(summary.get("payments")).hasSize(2);
		assertThat(summary.get("paymentStatus").asString()).isEqualTo("PAID");
		assertThat(summary.has("total") || summary.has("grandTotal") || summary.has("amount")).isFalse();        // no combined figure: only per-currency balances
		assertThat(summary.get("balances")).hasSize(2);
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", r.id().toString())).extracting(a -> a.getAction()).contains("PAYMENT_RECORDED");
	}

	@Test
	void paymentsAreOnlyAcceptedWhileTheReservationIsReadyAndMustBeValid() throws Exception {
		var pending = submit(key(), line(mattress, 1, SupportedCurrency.HTG));
		assertThat(payStatus(pending, Map.of("amount", "100.00", "currency", "HTG"), key())).isEqualTo(409);       // invalid_status
		var r = ready(submit(key(), line(mattress, 1, SupportedCurrency.HTG)));
		assertThat(payStatus(r, Map.of("amount", "100.00", "currency", "HTG"), null)).isEqualTo(422);              // key required
		assertThat(payStatus(r, Map.of("amount", "0", "currency", "HTG"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("amount", "-5.00", "currency", "HTG"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("amount", "10.005", "currency", "HTG"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("amount", "abc", "currency", "HTG"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("amount", "10.00", "currency", "EUR"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("amount", "10.00"), key())).isEqualTo(422);
		assertThat(payStatus(r, Map.of("currency", "HTG"), key())).isEqualTo(422);
		post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", "10.00", "currency", "USD"), staffToken, key()).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("currency_invalid"));                                         // this reservation has no USD balance
		assertThat(payStatus(r, Map.of("amount", "10.00", "currency", "HTG", "method", "BITCOIN"), key())).isEqualTo(422);
		post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", "25000.01", "currency", "HTG"), staffToken, key()).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("overpayment_not_allowed"));                                  // D3: one cent above the balance (25000.00)
		post(ADMIN + "/reservations/" + UUID.randomUUID() + "/payments", Map.of("amount", "10.00", "currency", "HTG"), staffToken, key()).andExpect(status().isNotFound());
		assertThat(JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/payments", staffToken).andReturn().getResponse().getContentAsString()).get("payments")).isEmpty();
	}

	@Test
	void aRetriedPaymentIsRecordedOnceAndAKeyReusedWithOtherDataIsAConflict() throws Exception {
		var r = readyBothCurrencies();
		var key = key();
		var first = pay(r, "HTG", "5000.00", key);
		var again = JSON.readTree(post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", "5000.00", "currency", "HTG"), staffToken, key).andReturn().getResponse().getContentAsString());
		assertThat(again.get("payment").get("id")).isEqualTo(first.get("payment").get("id"));
		assertThat(balance(again.get("balances"), "HTG").get("paid").asString()).isEqualTo("5000.00");        // not 10000
		post(ADMIN + "/reservations/" + r.id() + "/payments", Map.of("amount", "6000.00", "currency", "HTG"), staffToken, key).andExpect(status().isConflict());
		assertThat(JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/ledger", staffToken).andReturn().getResponse().getContentAsString())).hasSize(1);
	}

	@Test
	void aReversalIsANewLedgerEntryThatRestoresTheBalanceAndCanOnlyHappenOnce() throws Exception {
		var r = readyBothCurrencies();
		var paid = pay(r, "USD", "100.00", key());
		var paymentId = paid.get("payment").get("id").asString();
		post(ADMIN + "/payments/" + paymentId + "/reversal", Map.of(), staffToken, key()).andExpect(status().isUnprocessableContent());               // reason required
		post(ADMIN + "/payments/" + paymentId + "/reversal", Map.of("reason", "wrong amount"), staffToken, null).andExpect(status().isUnprocessableContent());   // key required
		var key = key();
		var reversed = JSON.readTree(post(ADMIN + "/payments/" + paymentId + "/reversal", Map.of("reason", "wrong amount"), staffToken, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(reversed.get("payment").get("reversed").asBoolean()).isTrue();
		assertThat(reversed.get("payment").get("reversal").get("reason").asString()).isEqualTo("wrong amount");
		assertThat(balance(reversed.get("balances"), "USD").get("paid").asString()).isEqualTo("0.00");
		assertThat(balance(reversed.get("balances"), "USD").get("due").asString()).isEqualTo("180.00");
		post(ADMIN + "/payments/" + paymentId + "/reversal", Map.of("reason", "wrong amount"), staffToken, key).andExpect(status().isOk());                  // same key: replay
		post(ADMIN + "/payments/" + paymentId + "/reversal", Map.of("reason", "again"), staffToken, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_reversed"));

		var ledger = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/ledger", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(ledger).hasSize(2);                                                        // the original entry is still there, plus its compensation
		assertThat(ledger.get(0).get("type").asString()).isEqualTo("PAYMENT_RECEIVED");
		assertThat(ledger.get(0).get("amount").asString()).isEqualTo("100.00");
		assertThat(ledger.get(1).get("type").asString()).isEqualTo("PAYMENT_REVERSAL");
		assertThat(ledger.get(1).get("amount").asString()).isEqualTo("-100.00");
		assertThat(ledger.get(1).get("reversesEntryId").asString()).isEqualTo(ledger.get(0).get("id").asString());
		get(ADMIN + "/payments/" + paymentId, staffToken).andExpect(jsonPath("$.reversed").value(true)).andExpect(jsonPath("$.amount").value("100.00"));   // the payment itself is unchanged
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", r.id().toString())).extracting(a -> a.getAction()).contains("PAYMENT_RECORDED", "PAYMENT_REVERSED");
		get(ADMIN + "/payments/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound());
	}

	@Test
	void adjustmentsAreSignedNeedAReasonAndNeverTakePaidBelowZero() throws Exception {
		var r = readyBothCurrencies();
		pay(r, "HTG", "1000.00", key());
		var path = ADMIN + "/reservations/" + r.id() + "/payment-adjustments";
		post(path, Map.of("amount", "-200.00", "currency", "HTG"), staffToken, key()).andExpect(status().isUnprocessableContent());                           // reason required
		post(path, Map.of("amount", "-200.00", "currency", "HTG", "reason", "counting error"), staffToken, null).andExpect(status().isUnprocessableContent()); // key required
		post(path, Map.of("amount", "0.00", "currency", "HTG", "reason", "nothing"), staffToken, key()).andExpect(status().isUnprocessableContent());
		post(path, Map.of("amount", "-5000.00", "currency", "HTG", "reason", "too much"), staffToken, key()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("paid_negative"));
		var adjusted = JSON.readTree(post(path, Map.of("amount", "-200.00", "currency", "HTG", "reason", "counting error"), staffToken, key()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		assertThat(adjusted.get("type").asString()).isEqualTo("ADJUSTMENT");
		assertThat(adjusted.get("amount").asString()).isEqualTo("-200.00");
		var summary = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/payments", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(balance(summary.get("balances"), "HTG").get("paid").asString()).isEqualTo("800.00");
		assertThat(balance(summary.get("balances"), "USD").get("paid").asString()).isEqualTo("0.00");                   // the other currency is untouched
	}

	@Test
	void theCachedPaidAmountAlwaysReconcilesWithTheLedger() throws Exception {
		var r = readyBothCurrencies();
		var p = pay(r, "HTG", "3000.00", key());
		pay(r, "USD", "50.00", key());
		post(ADMIN + "/payments/" + p.get("payment").get("id").asString() + "/reversal", Map.of("reason", "oops"), staffToken, key()).andExpect(status().isOk());
		post(ADMIN + "/reservations/" + r.id() + "/payment-adjustments", Map.of("amount", "10.00", "currency", "USD", "reason", "tip"), staffToken, key()).andExpect(status().isCreated());
		var report = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/reconciliation", staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(report.get("consistent").asBoolean()).isTrue();
		assertThat(report.get("lines")).hasSize(2);
		assertThat(report.get("lines").toString()).contains("\"ledgerPaid\":\"60.00\"");
		get(ADMIN + "/reservations/" + UUID.randomUUID() + "/reconciliation", staffToken).andExpect(status().isNotFound());
	}

	@Test
	void simultaneousPaymentsAgainstOneBalanceStopOnceItIsSettled() throws Exception {
		var r = ready(submit(key(), line(mattress, 1, SupportedCurrency.HTG)));           // 25000.00 due
		int threads = 6;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(threads);
		var results = new ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return payStatus(r, Map.of("amount", "5000.00", "currency", "HTG"), key());
			}));
		}
		start.countDown();
		var codes = new ArrayList<Integer>();
		int ok = 0;
		int conflict = 0;
		for (var f : results) {
			int code = f.get();
			codes.add(code);
			if (code == 201) {
				ok++;
			} else if (code == 409) {
				conflict++;
			}
		}
		pool.shutdown();
		assertThat(ok).as("status codes " + codes).isEqualTo(5);                      // 5 x 5000 settles 25000 exactly; the sixth sees it settled (D3: nothing above the balance is ever recorded)
		assertThat(conflict).as("status codes " + codes).isEqualTo(threads - 5);
		var summary = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/payments", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(summary.get("paymentStatus").asString()).isEqualTo("PAID");
		assertThat(summary.get("payments")).hasSize(5);
		var report = JSON.readTree(get(ADMIN + "/reservations/" + r.id() + "/reconciliation", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(report.get("consistent").asBoolean()).isTrue();
	}

	@Test
	void theListFiltersByCurrencyReversalPeriodAndReservationAndNeverSumsCurrencies() throws Exception {
		var r = readyBothCurrencies();
		var htg = pay(r, "HTG", "1000.00", key()).get("payment").get("id").asString();
		var usd = pay(r, "USD", "20.00", key()).get("payment").get("id").asString();
		post(ADMIN + "/payments/" + usd + "/reversal", Map.of("reason", "test"), staffToken, key()).andExpect(status().isOk());
		var base = ADMIN + "/payments?reservationId=" + r.id();
		get(base, staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(base + "&currency=HTG", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(htg));
		get(base + "&currency=USD", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(usd));
		get(base + "&reversed=true", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(usd));
		get(base + "&reversed=false", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(htg));
		get(base + "&from=2000-01-01&to=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(base + "&from=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(base + "&method=CASH_AT_PICKUP&size=1&page=1", staffToken).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
		get(base + "&from=2999-01-01&to=2000-01-01", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("date_range_invalid"));
		get(base + "&currency=EUR", staffToken).andExpect(status().isUnprocessableContent());
		get(base + "&reversed=maybe", staffToken).andExpect(status().isBadRequest());
		var item = JSON.readTree(get(base + "&currency=HTG", staffToken).andReturn().getResponse().getContentAsString()).get("items").get(0);
		assertThat(item.get("reservationReference").asString()).isEqualTo(r.reference());
		assertThat(item.get("amount").asString()).isEqualTo("1000.00");
	}
}
