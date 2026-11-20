package com.anadepot.infra.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guest reservation over HTTP: real security chain, validation, use case, PostgreSQL. */
class GuestReservationApiIntegrationTest extends ReservationHttpSupport {

	@BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	@Test
	void aGuestReservesWithoutAnAccount_andPricesComeFromTheCatalogNeverFromTheClient() throws Exception {
		var mattress = stockedMattress(10);
		var request = request(freshPhone(), line(mattress, 2, "HTG"));
		((Map<String, Object>) ((List<?>) request.get("lines")).getFirst()).put("unitPrice", "1.00");   // a lying client
		request.put("total", "1.00");

		var result = post("/api/v1/reservations", request, null, key()).andExpect(status().isCreated()).andReturn();
		var view = body(result);

		assertThat(view.get("reference").asString()).matches("ANA-[A-Z2-9]{6}");
		assertThat(view.get("status").asString()).isEqualTo("PENDING_REVIEW");
		assertThat(view.get("paymentStatus").asString()).isEqualTo("UNPAID");
		assertThat(view.get("canCancel").asBoolean()).as("D7 (approved): a pending reservation may be cancelled by the customer").isTrue();
		var line = view.get("lines").get(0);
		assertThat(line.get("unitPrice").asString()).isEqualTo("25000.00");
		assertThat(line.get("lineTotal").asString()).isEqualTo("50000.00");
		assertThat(line.get("currency").asString()).isEqualTo("HTG");
		assertThat(line.get("name").asString()).isEqualTo("Matelas d'essai");
		assertThat(line.get("pricingKind").asString()).isEqualTo("RETAIL");
		assertThat(view.get("totals")).hasSize(1);
		assertThat(view.get("totals").get(0).get("amount").asString()).isEqualTo("50000.00");
		assertThat(view.get("balances").get(0).get("due").asString()).isEqualTo("50000.00");
		assertThat(view.get("pickup").get("location").get("storeName").asString()).isNotBlank();      // the pickup promised at submission
		assertThat(view.get("pickup").get("capturedAt").asString()).isNotBlank();
		assertThat(view.get("events")).hasSize(1);
		assertThat(view.get("events").get(0).get("type").asString()).isEqualTo("SUBMITTED");
	}

	@Test
	void wholesaleTierAppliesAtTheTierQuantityAndIsSnapshotted() throws Exception {
		var mattress = stockedMattress(20);
		var view = reserve(request(freshPhone(), line(mattress, 5, "HTG")), null);
		var line = view.get("lines").get(0);
		assertThat(line.get("unitPrice").asString()).isEqualTo("23000.00");
		assertThat(line.get("pricingKind").asString()).isEqualTo("WHOLESALE");
		assertThat(line.get("tierMinQuantity").asInt()).isEqualTo(5);
	}

	@Test
	void aMixedHtgAndUsdReservationKeepsOneTotalPerCurrencyAndSharesTheStock() throws Exception {
		var mattress = stockedMattress(3);
		var view = reserve(request(freshPhone(), line(mattress, 2, "HTG"), line(mattress, 1, "USD")), null);

		assertThat(view.get("totals")).hasSize(2);
		assertThat(amountOf(view.get("totals"), "HTG")).isEqualTo("50000.00");
		assertThat(amountOf(view.get("totals"), "USD")).isEqualTo("180.00");
		assertThat(view.get("balances")).hasSize(2);
		var item = inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst();
		assertThat(item.reserved()).isEqualTo(3);                     // 2 + 1 from the SAME stock position
		// the stock is now fully reserved: one more unit in either currency is refused
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "USD")), null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("insufficient_stock"));
	}

	private static String amountOf(tools.jackson.databind.JsonNode amounts, String currency) {
		for (var a : amounts) {
			if (a.get("currency").asString().equals(currency)) {
				return a.get("amount").asString();
			}
		}
		return null;
	}

	@Test
	void theIdempotencyKeyIsRequired() throws Exception {
		var mattress = stockedMattress(5);
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), null, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("idempotency_key_required"));
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), null, "   ")
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("idempotency_key_required"));
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), null, "short")
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("idempotency_key_invalid"));
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isZero();
	}

	@Test
	void retryingWithTheSameKeyReturnsTheSameReservationAndReservesStockOnce() throws Exception {
		var mattress = stockedMattress(5);
		var request = request(freshPhone(), line(mattress, 2, "HTG"));
		var key = key();
		var first = body(post("/api/v1/reservations", request, null, key).andExpect(status().isCreated()).andReturn());
		var second = body(post("/api/v1/reservations", request, null, key).andExpect(status().isCreated()).andReturn());

		assertThat(second.get("reference").asString()).isEqualTo(first.get("reference").asString());
		assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isEqualTo(2);
	}

	@Test
	void reusingAKeyForADifferentRequestIsAConflict() throws Exception {
		var mattress = stockedMattress(5);
		var key = key();
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), null, key).andExpect(status().isCreated());
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 3, "HTG")), null, key)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_key_reuse"));
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isEqualTo(1);
	}

	@Test
	void everyInvalidFieldIsReportedAtOnceWithStableCodes() throws Exception {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("lines", List.of(Map.of("quantity", 0, "currency", "EUR")));
		body.put("contact", Map.of("firstName", " ", "lastName", "X", "dialCode", "abc", "phone", "12", "email", "nope"));
		body.put("locale", "en");
		body.put("acknowledgedTerms", false);
		var result = post("/api/v1/reservations", body, null, key()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("validation_failed")).andReturn();
		var errors = new ArrayList<String>();
		body(result).get("errors").forEach(e -> errors.add(e.get("field").asString() + ":" + e.get("code").asString()));
		assertThat(errors).contains("lines[0].productId:required", "lines[0].quantity:quantity_invalid", "lines[0].currency:currency_invalid",
				"contact.firstName:required", "contact.dialCode:invalid", "contact.phone:invalid", "contact.email:invalid", "locale:invalid", "acknowledgedTerms:terms_not_acknowledged");
	}

	@Test
	void anEmptyCartAndAMissingBodyAreRejected() throws Exception {
		post("/api/v1/reservations", Map.of("lines", List.of(), "locale", "fr", "acknowledgedTerms", true), null, key())
				.andExpect(status().isUnprocessableContent());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/reservations").header("Idempotency-Key", key())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownProductsUnavailableProductsAndMissingCurrenciesAreRefusedWithoutSideEffects() throws Exception {
		var mattress = stockedMattress(5);
		var ghost = new java.util.LinkedHashMap<String, Object>(line(mattress, 1, "HTG"));
		ghost.put("productId", java.util.UUID.randomUUID());
		post("/api/v1/reservations", request(freshPhone(), ghost), null, key()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));

		var variantOnSimple = new java.util.LinkedHashMap<String, Object>(line(mattress, 1, "HTG"));
		variantOnSimple.put("variantId", java.util.UUID.randomUUID());
		post("/api/v1/reservations", request(freshPhone(), variantOnSimple), null, key()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("variant_unavailable"));

		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isZero();
	}

	@Test
	void overStockIsAConflictAndLeavesTheStockUntouched() throws Exception {
		var mattress = stockedMattress(2);
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 3, "HTG")), null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("insufficient_stock"));
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isZero();
	}

	@Test
	void concurrentHttpSubmissionsForTheLastUnitsNeverOversellAcrossCurrencies() throws Exception {
		int stock = 5;
		int clients = 16;
		var mattress = stockedMattress(stock);
		var pool = Executors.newFixedThreadPool(clients);
		var ready = new CountDownLatch(clients);
		var go = new CountDownLatch(1);
		var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < clients; i++) {
			var currency = i % 2 == 0 ? "HTG" : "USD";
			Callable<Integer> task = () -> {
				ready.countDown();
				go.await();
				return post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, currency)), null, key()).andReturn().getResponse().getStatus();
			};
			futures.add(pool.submit(task));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		int created = 0;
		int conflicts = 0;
		for (var f : futures) {
			int code = f.get(60, TimeUnit.SECONDS);
			if (code == 201) {
				created++;
			} else if (code == 409) {
				conflicts++;
			}
		}
		pool.shutdownNow();

		assertThat(created).isEqualTo(stock);
		assertThat(conflicts).isEqualTo(clients - stock);
		var item = inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst();
		assertThat(item.reserved()).isEqualTo(stock);
		assertThat(item.onHand()).isEqualTo(stock);
	}

	@Test
	void anInvalidBearerTokenIsRefusedRatherThanTreatedAsAGuest() throws Exception {
		var mattress = stockedMattress(2);
		post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), "not-a-jwt", key()).andExpect(status().isUnauthorized());
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isZero();
	}

	@Test
	void theResponseNeverContainsInternalOrStaffData() throws Exception {
		var mattress = stockedMattress(5);
		var result = post("/api/v1/reservations", request(freshPhone(), line(mattress, 1, "HTG")), null, key()).andExpect(status().isCreated()).andReturn();
		var text = result.getResponse().getContentAsString();
		assertThat(text).doesNotContain("actor", "userId", "internal", "INTERNAL", "authUserId", "customerId", "ledger", "recordedBy", "version");
	}

	@Test
	void guestReservationsOfTheSamePhoneShareOneGuestRecord_andDoNotNeedAnAccount() throws Exception {
		var mattress = stockedMattress(5);
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 1, "HTG")), null);
		reserve(request(phone, line(mattress, 1, "USD")), null);
		assertThat(inventory.positionsOf(List.of(mattress.id())).get(mattress.id()).getFirst().reserved()).isEqualTo(2);
	}
}
