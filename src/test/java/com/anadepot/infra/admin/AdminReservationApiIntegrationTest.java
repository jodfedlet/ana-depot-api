package com.anadepot.infra.admin;

import static com.anadepot.testsupport.TestData.htg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff reservation management over HTTP: the real security chain, real use cases and PostgreSQL. */
class AdminReservationApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BASE = "/api/v1/admin/reservations";
	@Autowired AuditEventJpaRepository audits;

	private String staffToken;

	@BeforeEach
	void setUp() {
		staffToken = newStaffToken();
		ensurePickupAvailable();
	}

	private Reservation reserve(Product p, int quantity, SupportedCurrency currency) {
		return submit(key(), line(p, quantity, currency));
	}

	private int available(Product p) {
		return inventory.get(p.id(), null).available();
	}

	private ResultActionsHolder act(String id, String action, Object body, String key) throws Exception {
		return new ResultActionsHolder(post(BASE + "/" + id + "/" + action, body == null ? Map.of() : body, staffToken, key).andReturn());
	}

	private record ResultActionsHolder(org.springframework.test.web.servlet.MvcResult result) {
		int status() {
			return result.getResponse().getStatus();
		}

		JsonNode json() throws Exception {
			return JSON.readTree(result.getResponse().getContentAsString());
		}
	}

	private void pay(Reservation r, String amount) {
		payments.record(new RecordPaymentCommand(r.id(), htg(amount), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
	}

	private JsonNode read(String id) throws Exception {
		return JSON.readTree(get(BASE + "/" + id, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryReservationEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get(BASE, bearer).andExpect(status().is(expected));
			get(BASE + "/" + id, bearer).andExpect(status().is(expected));
			for (var action : List.of("accept", "reject", "ready", "collect", "cancel", "notes")) {
				post(BASE + "/" + id + "/" + action, Map.of(), bearer, null).andExpect(status().is(expected));
			}
		}
	}

	@Test
	void staffSeesTheFullDetailWithInternalNotesActorsTermsAndAllowedActions() throws Exception {
		var mattress = stockedMattress(10);
		var r = reserve(mattress, 2, SupportedCurrency.HTG);
		var detail = read(r.id().toString());
		assertThat(detail.get("reference").asString()).isEqualTo(r.reference());
		assertThat(detail.get("status").asString()).isEqualTo("PENDING_REVIEW");
		assertThat(detail.get("paymentStatus").asString()).isEqualTo("UNPAID");
		assertThat(detail.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
		assertThat(detail.get("lines").get(0).get("unitPrice").asString()).isEqualTo("25000.00");
		assertThat(detail.get("balances").get(0).get("due").asString()).isEqualTo("50000.00");
		assertThat(detail.get("history").get(0).get("actor").get("kind").asString()).isEqualTo("CUSTOMER");
		assertThat(detail.get("allowedActions").toString()).contains("ACCEPT", "REJECT", "CANCEL", "ADD_NOTE").doesNotContain("COLLECT");
		get(BASE + "/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound());
	}

	@Test
	void theWholeLifecycleAcceptReadyPayCollectMovesStockAndEachStepIsAudited() throws Exception {
		var mattress = stockedMattress(10);
		var r = reserve(mattress, 3, SupportedCurrency.HTG);
		var id = r.id().toString();
		assertThat(available(mattress)).isEqualTo(7);
		assertThat(act(id, "accept", null, null).status()).isEqualTo(200);
		assertThat(act(id, "ready", null, null).status()).isEqualTo(200);
		var early = act(id, "collect", null, null);
		assertThat(early.status()).isEqualTo(409);                                              // nothing is paid yet
		assertThat(early.json().get("code").asString()).isEqualTo("payment_required");
		pay(r, "75000.00");
		assertThat(read(id).get("allowedActions").toString()).contains("COLLECT");
		var collected = act(id, "collect", null, null);
		assertThat(collected.status()).isEqualTo(200);
		assertThat(collected.json().get("status").asString()).isEqualTo("COLLECTED");
		assertThat(inventory.get(mattress.id(), null).onHand()).isEqualTo(7);                   // 10 - 3 handed over
		assertThat(inventory.get(mattress.id(), null).reserved()).isZero();
		assertThat(act(id, "cancel", Map.of("reason", "late"), null).status()).isEqualTo(409);  // a collected reservation cannot be cancelled
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", id)).extracting(a -> a.getAction())
				.contains("RESERVATION_ACCEPTED", "RESERVATION_READY", "RESERVATION_COLLECTED");
		get(BASE + "?status=COLLECTED&q=" + r.reference(), staffToken).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void rejectNeedsAReasonReleasesStockAndOtherNeedsAnExplanation() throws Exception {
		var mattress = stockedMattress(5);
		var r = reserve(mattress, 5, SupportedCurrency.USD);
		var id = r.id().toString();
		assertThat(available(mattress)).isZero();
		assertThat(act(id, "reject", Map.of(), null).status()).isEqualTo(422);
		assertThat(act(id, "reject", Map.of("reason", "NOPE"), null).status()).isEqualTo(422);
		var other = act(id, "reject", Map.of("reason", "OTHER"), null);
		assertThat(other.status()).isEqualTo(422);
		assertThat(other.json().get("code").asString()).isEqualTo("explanation_required");
		assertThat(available(mattress)).isZero();                                               // refused: still held
		var rejected = act(id, "reject", Map.of("reason", "PRODUCT_UNAVAILABLE", "note", "damaged in storage"), null);
		assertThat(rejected.status()).isEqualTo(200);
		assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");
		assertThat(available(mattress)).isEqualTo(5);                                           // released
		assertThat(act(id, "accept", null, null).status()).isEqualTo(409);
	}

	@Test
	void cancelNeedsAReasonAndReleasesTheStock() throws Exception {
		var mattress = stockedMattress(5);
		var r = reserve(mattress, 2, SupportedCurrency.HTG);
		var id = r.id().toString();
		act(id, "accept", null, null);
		assertThat(act(id, "cancel", Map.of(), null).status()).isEqualTo(422);
		assertThat(act(id, "cancel", Map.of("reason", "  "), null).status()).isEqualTo(422);
		assertThat(available(mattress)).isEqualTo(3);
		var cancelled = act(id, "cancel", Map.of("reason", "customer called"), null);
		assertThat(cancelled.status()).isEqualTo(200);
		assertThat(cancelled.json().get("history").toString()).contains("customer called");
		assertThat(available(mattress)).isEqualTo(5);
	}

	@Test
	void internalNotesNeverReachTheCustomerButCustomerVisibleOnesDoAndTheAuditNeverHoldsTheText() throws Exception {
		var mattress = stockedMattress(5);
		var phone = "37" + String.format("%06d", (int) (Math.random() * 1_000_000));
		var r = submit(key(), line(mattress, 1, SupportedCurrency.HTG), phone);
		var id = r.id().toString();
		var secret = "internal-" + UUID.randomUUID();
		var visible = "visible-" + UUID.randomUUID();
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", secret), null).status()).isEqualTo(201);
		assertThat(act(id, "notes", Map.of("visibility", "CUSTOMER_VISIBLE", "body", visible), null).status()).isEqualTo(201);
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", "  "), null).status()).isEqualTo(422);
		assertThat(act(id, "notes", Map.of("visibility", "WHO", "body", "x"), null).status()).isEqualTo(422);
		assertThat(act(id, "notes", Map.of("body", "x"), null).status()).isEqualTo(422);
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", "x".repeat(2001)), null).status()).isEqualTo(422);
		var detail = read(id);
		assertThat(detail.get("notes")).hasSize(2);
		assertThat(detail.get("notes").toString()).contains(secret, visible);
		assertThat(detail.get("notes").get(0).get("author").get("kind").asString()).isEqualTo("STAFF");

		var customerView = post("/api/v1/reservations/lookup", Map.of("reference", r.reference(), "phone", phone), null, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(customerView).contains(visible).doesNotContain(secret);
		var trail = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", id);
		assertThat(trail).extracting(a -> a.getAction()).contains("RESERVATION_NOTE_ADDED");
		var dump = trail.stream().map(a -> a.getPreviousValues() + " " + a.getNewValues() + " " + a.getReason()).collect(java.util.stream.Collectors.joining(" "));
		assertThat(dump).contains("INTERNAL").doesNotContain(secret).doesNotContain(visible);
	}

	@Test
	void aRetriedNoteWithTheSameKeyIsAddedOnce() throws Exception {
		var r = reserve(stockedMattress(5), 1, SupportedCurrency.HTG);
		var id = r.id().toString();
		var key = key();
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", "once"), key).status()).isEqualTo(201);
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", "once"), key).json().get("notes")).hasSize(1);
		assertThat(act(id, "notes", Map.of("visibility", "INTERNAL", "body", "different"), key).status()).isEqualTo(409);
		assertThat(read(id).get("notes")).hasSize(1);
	}

	@Test
	void aRetriedTransitionWithTheSameKeyReturnsTheOriginalResultWhileAPlainRepeatIsAConflict() throws Exception {
		var r = reserve(stockedMattress(5), 1, SupportedCurrency.HTG);
		var id = r.id().toString();
		var key = key();
		assertThat(act(id, "accept", null, key).status()).isEqualTo(200);
		var replay = act(id, "accept", null, key);
		assertThat(replay.status()).isEqualTo(200);
		assertThat(replay.json().get("status").asString()).isEqualTo("CONFIRMED");
		assertThat(act(id, "accept", null, null).status()).isEqualTo(409);
		assertThat(act(id, "accept", null, key()).json().get("code").asString()).isEqualTo("invalid_status");
		assertThat(read(id).get("history")).hasSize(2);                                          // submitted + accepted, once
	}

	@Test
	void simultaneousTransitionsOnOneReservationLetExactlyOneWin() throws Exception {
		var mattress = stockedMattress(5);
		var r = reserve(mattress, 2, SupportedCurrency.HTG);
		var id = r.id().toString();
		int threads = 6;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(threads);
		var results = new ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < threads; i++) {
			var action = i % 2 == 0 ? "accept" : "reject";
			results.add(pool.submit(() -> {
				start.await();
				return act(id, action, action.equals("reject") ? Map.of("reason", "CANNOT_FULFILL") : null, key()).status();
			}));
		}
		start.countDown();
		var codes = new ArrayList<Integer>();
		int ok = 0;
		int conflict = 0;
		for (var f : results) {
			int code = f.get();
			codes.add(code);
			if (code == 200) {
				ok++;
			} else if (code == 409) {
				conflict++;
			}
		}
		pool.shutdown();
		assertThat(ok).as("status codes " + codes).isEqualTo(1);
		assertThat(conflict).as("status codes " + codes).isEqualTo(threads - 1);
		var status = read(id).get("status").asString();
		assertThat(status).isIn("CONFIRMED", "REJECTED");
		assertThat(available(mattress)).isEqualTo(status.equals("REJECTED") ? 5 : 3);           // stock released exactly when rejected, never twice
	}

	@Test
	void listFiltersByStatusPaymentCurrencyProductTextPeriodAndSort() throws Exception {
		var mattress = stockedMattress(50);
		var phone = "38" + String.format("%06d", (int) (Math.random() * 1_000_000));
		var a = submit(key(), line(mattress, 1, SupportedCurrency.HTG), phone);
		var b = reserve(mattress, 1, SupportedCurrency.USD);
		var c = reserve(mattress, 2, SupportedCurrency.HTG);
		act(c.id().toString(), "accept", null, null);
		act(c.id().toString(), "ready", null, null);
		pay(c, "20000.00");                                                                      // partial: 50000 due

		get(BASE + "?productId=" + mattress.id(), staffToken).andExpect(jsonPath("$.totalItems").value(3));
		get(BASE + "?productId=" + mattress.id() + "&status=PENDING_REVIEW", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?productId=" + mattress.id() + "&status=READY_FOR_PICKUP&paymentStatus=PARTIAL", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].reference").value(c.reference()));
		get(BASE + "?productId=" + mattress.id() + "&paymentStatus=UNPAID", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?productId=" + mattress.id() + "&paymentStatus=PAID", staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?productId=" + mattress.id() + "&currency=USD", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].reference").value(b.reference()));
		get(BASE + "?q=" + a.reference().toLowerCase(), staffToken).andExpect(jsonPath("$.totalItems").value(1));
		get(BASE + "?q=" + phone, staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].reference").value(a.reference()));
		get(BASE + "?q=Marie&productId=" + mattress.id(), staffToken).andExpect(jsonPath("$.totalItems").value(3));
		get(BASE + "?productId=" + mattress.id() + "&sort=OLDEST", staffToken).andExpect(jsonPath("$.items[0].reference").value(a.reference()));
		get(BASE + "?productId=" + mattress.id(), staffToken).andExpect(jsonPath("$.items[0].reference").value(c.reference()));      // newest first by default
		get(BASE + "?productId=" + mattress.id() + "&from=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?productId=" + mattress.id() + "&from=2000-01-01&to=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(3));
		get(BASE + "?from=2999-01-01&to=2000-01-01", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("date_range_invalid"));
		get(BASE + "?from=yesterday", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "?status=NOPE", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "?q=%25_%5C", staffToken).andExpect(status().isOk());
		get(BASE + "?productId=" + mattress.id() + "&size=2&page=1", staffToken).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
		var summary = JSON.readTree(get(BASE + "?q=" + c.reference(), staffToken).andReturn().getResponse().getContentAsString()).get("items").get(0);
		assertThat(summary.get("balances").get(0).get("paid").asString()).isEqualTo("20000.00");
		assertThat(summary.get("balances").get(0).get("due").asString()).isEqualTo("30000.00");
		assertThat(summary.get("unitCount").asInt()).isEqualTo(2);
	}
}
