package com.anadepot.infra.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.CheckoutGateway;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Online payment over HTTP with the REAL security chain, controllers, use cases, PostgreSQL and the REAL webhook signature check. The provider itself is a
 * deterministic fake (MOCKED Stripe): no request leaves the machine. Webhook payloads are signed with the same HMAC scheme Stripe uses.
 */
@TestPropertySource(properties = {
		"ana.stripe.webhook-secret=whsec_unit_test_only",
		"ana.rate-limit.limits[payment.checkout].max-requests=100000", "ana.rate-limit.limits[payment.checkout].window=PT1M",
		"ana.rate-limit.limits[payment.status].max-requests=100000", "ana.rate-limit.limits[payment.status].window=PT1M" })
class OnlinePaymentIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String SECRET = "whsec_unit_test_only";

	/** A deterministic stand-in for the provider: enabled/eligibility are switchable, sessions are idempotent per attempt like Stripe's. */
	static class FakeGateway implements CheckoutGateway {
		volatile boolean enabled = true;
		volatile boolean providerDown = false;   // switched on, but the provider cannot be asked
		volatile Set<SupportedCurrency> eligible = Set.of(SupportedCurrency.USD);
		final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
		final AtomicInteger calls = new AtomicInteger();

		@Override public boolean enabled() { return enabled; }
		@Override public Set<SupportedCurrency> eligibleCurrencies() { return providerDown ? Set.of() : eligible; }
		@Override public Availability availability() { return !enabled ? Availability.DISABLED : providerDown ? Availability.TEMPORARILY_UNAVAILABLE : CheckoutGateway.super.availability(); }
		@Override public Optional<Money> minimumCharge(SupportedCurrency c) { return c == SupportedCurrency.USD ? Optional.of(Money.of("0.50", c)) : Optional.empty(); }
		@Override public Session createSession(Request r) {
			calls.incrementAndGet();
			return sessions.computeIfAbsent(r.attemptId(), id -> new Session("cs_test_" + id, "https://checkout.stripe.com/c/pay/cs_test_" + id, Instant.now().plusSeconds(1800)));
		}
	}

	@TestConfiguration
	static class Fake {
		@Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); }
	}

	@Autowired FakeGateway gateway;
	@Autowired PaymentRepository ledger;

	Product mattress;

	@BeforeEach
	void setUp() {
		gateway.enabled = true;
		gateway.providerDown = false;
		gateway.eligible = Set.of(SupportedCurrency.USD);
		gateway.sessions.clear();
		gateway.calls.set(0);
		ensurePickupAvailable();
		mattress = stockedMattress(50);
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	private Reservation readyBoth() {
		return ready(submit(key(), line(mattress, 1, SupportedCurrency.HTG), line(mattress, 1, SupportedCurrency.USD)));
	}

	private static String phone(Reservation r) {
		return r.contact().dialCode() + " " + r.contact().phone(); // with the dial code: a national number starting "00" would read as an international prefix
	}

	private JsonNode checkout(Reservation r, String currency, String key, int expectedStatus) throws Exception {
		var res = post("/api/v1/reservations/" + r.reference() + "/payments/checkout-session", Map.of("currency", currency, "phone", phone(r)), null, key).andReturn();
		assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expectedStatus);
		return JSON.readTree(res.getResponse().getContentAsString());
	}

	private JsonNode paymentStatus(Reservation r) throws Exception {
		var res = post("/api/v1/reservations/" + r.reference() + "/payments/status", Map.of("phone", phone(r)), null, null).andExpect(status().isOk()).andReturn();
		return JSON.readTree(res.getResponse().getContentAsString());
	}

	private static String sign(String payload) throws Exception {
		long t = Instant.now().getEpochSecond();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
	}

	private int webhook(String payload, String signature) throws Exception {
		var req = MockMvcRequestBuilders.post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload.getBytes(StandardCharsets.UTF_8));
		if (signature != null) {
			req.header("Stripe-Signature", signature);
		}
		return mvc.perform(req).andReturn().getResponse().getStatus();
	}

	private int deliver(String payload) throws Exception {
		return webhook(payload, sign(payload));
	}

	private static String sessionEvent(String eventId, String type, JsonNode attempt, String intent, String amountMinor, String currency, String paymentStatus) {
		var id = attempt.get("attemptId").asString();
		return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"cs_test_" + id + "\",\"object\":\"checkout.session\",\"payment_intent\":\"" + intent
				+ "\",\"amount_total\":" + amountMinor + ",\"currency\":\"" + currency + "\",\"payment_status\":\"" + paymentStatus + "\",\"metadata\":{\"attempt_id\":\"" + id + "\"}}}}";
	}

	private long stripePayments(Reservation r) {
		return ledger.payments(r.id()).stream().filter(p -> p.method() == PaymentMethod.STRIPE_CHECKOUT).count();
	}

	private JsonNode balance(JsonNode status, String currency) {
		for (var b : status.get("reservation").get("balances")) {
			if (b.get("currency").asString().equals(currency)) {
				return b;
			}
		}
		throw new AssertionError("no " + currency);
	}

	// ---- options, availability ----------------------------------------------------------------------------------

	@Test
	void paymentOptionsListOnlyEligibleCurrenciesAndNullWhenOff() throws Exception {
		get("/api/v1/store/payment-options", null).andExpect(status().isOk()).andExpect(jsonPath("$.online.provider").value("STRIPE")).andExpect(jsonPath("$.online.currencies[0]").value("USD"))
				.andExpect(jsonPath("$.online.currencies.length()").value(1));
		gateway.eligible = Set.of();
		get("/api/v1/store/payment-options", null).andExpect(status().isOk()).andExpect(jsonPath("$.online").doesNotExist());
		gateway.enabled = false;
		get("/api/v1/store/payment-options", null).andExpect(status().isOk()).andExpect(jsonPath("$.online").doesNotExist());
	}

	private JsonNode options() throws Exception {
		return JSON.readTree(get("/api/v1/store/payment-options", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void theBackendStatesWhichPaymentOptionsExistRightNow() throws Exception {
		// both: Stripe available and pickup open
		var both = options();
		assertThat(both.get("onlineStatus").asString()).isEqualTo("AVAILABLE");
		assertThat(both.get("atPickup").get("available").asBoolean()).isTrue();
		assertThat(both.get("summary").asString()).isEqualTo("BOTH");
		assertThat(both.get("online").get("currencies").get(0).asString()).isEqualTo("USD");

		// Stripe switched off: only pay at pickup, and nothing online is advertised
		gateway.enabled = false;
		var off = options();
		assertThat(off.get("onlineStatus").asString()).isEqualTo("DISABLED");
		assertThat(off.get("summary").asString()).isEqualTo("PICKUP_ONLY");
		assertThat(off.has("online") && !off.get("online").isNull()).isFalse();

		// switched on, but the account can charge none of the configured currencies: not offered (DISABLED), never "available because the code exists"
		gateway.enabled = true;
		gateway.eligible = Set.of();
		var none = options();
		assertThat(none.get("onlineStatus").asString()).isEqualTo("DISABLED");
		assertThat(none.get("summary").asString()).isEqualTo("PICKUP_ONLY");

		// switched on, provider unreachable: temporarily unavailable, still no online option, pay at pickup untouched
		gateway.eligible = Set.of(SupportedCurrency.USD);
		gateway.providerDown = true;
		var down = options();
		assertThat(down.get("onlineStatus").asString()).isEqualTo("TEMPORARILY_UNAVAILABLE");
		assertThat(down.get("summary").asString()).isEqualTo("PICKUP_ONLY");
		assertThat(down.has("online") && !down.get("online").isNull()).isFalse();
		assertThat(down.get("atPickup").get("available").asBoolean()).isTrue();
	}

	@Test
	void whenPickupIsClosedNoPaymentMethodIsAvailableWhateverStripeSays() throws Exception {
		var current = storeSettings.getPickup();
		storeSettings.savePickup(com.anadepot.testsupport.TestData.testPickup(false), current.version(), staff);
		try {
			var closed = options();
			assertThat(closed.get("atPickup").get("available").asBoolean()).isFalse();
			assertThat(closed.get("summary").asString()).isEqualTo("NONE");
			assertThat(closed.get("onlineStatus").asString()).isEqualTo("AVAILABLE");   // reported as it is; the summary is what customers act on
		} finally {
			ensurePickupAvailable();
		}
	}

	@Test
	void disabledStripeCreatesNoSessionAndReservationsStillWork() throws Exception {
		var r = readyBoth();
		gateway.enabled = false;
		checkout(r, "USD", key(), 422);
		assertThat(gateway.calls.get()).isZero();
		assertThat(paymentStatus(r).get("attempts")).isEmpty();
		// pay at pickup is untouched: staff records cash as before
		var staffToken = newStaffToken();
		post("/api/v1/admin/reservations/" + r.id() + "/payments", Map.of("amount", "180.00", "currency", "USD"), staffToken, key()).andExpect(status().isCreated());
	}

	@Test
	void aCurrencyTheProviderCannotProcessIsRefusedAndNeverConverted() throws Exception {
		var r = readyBoth();
		var body = checkout(r, "HTG", key(), 422);
		assertThat(body.get("code").asString()).isEqualTo("currency_not_available_online");
		assertThat(gateway.calls.get()).isZero();
		assertThat(paymentStatus(r).get("attempts")).isEmpty();
	}

	// ---- creating a checkout ------------------------------------------------------------------------------------

	@Test
	void theAmountComesFromTheBackendNeverFromTheClient() throws Exception {
		var r = readyBoth();
		var res = post("/api/v1/reservations/" + r.reference() + "/payments/checkout-session", Map.of("currency", "USD", "phone", phone(r), "amount", "0.01"), null, key()).andExpect(status().isCreated()).andReturn();
		var body = JSON.readTree(res.getResponse().getContentAsString());
		assertThat(body.get("amount").asString()).isEqualTo("180.00");
		assertThat(body.get("currency").asString()).isEqualTo("USD");
		assertThat(body.get("checkoutUrl").asString()).startsWith("https://checkout.stripe.com/");
		assertThat(body.get("reused").asBoolean()).isFalse();
		// the HTG balance was not touched by a USD session
		var status = paymentStatus(r);
		assertThat(balance(status, "HTG").get("due").asString()).isEqualTo("25000.00");
		assertThat(balance(status, "USD").get("due").asString()).isEqualTo("180.00");
	}

	@Test
	void aSecondRequestReturnsTheOpenSessionInsteadOfCreatingAnother() throws Exception {
		var r = readyBoth();
		var first = checkout(r, "USD", key(), 201);
		var second = checkout(r, "USD", key(), 200);
		assertThat(second.get("attemptId").asString()).isEqualTo(first.get("attemptId").asString());
		assertThat(second.get("checkoutUrl").asString()).isEqualTo(first.get("checkoutUrl").asString());
		assertThat(second.get("reused").asBoolean()).isTrue();
		assertThat(gateway.calls.get()).isEqualTo(1);
	}

	@Test
	void theSameKeyWithAnotherCurrencyIsRefusedAndAMissingKeyIsRequired() throws Exception {
		var r = readyBoth();
		gateway.eligible = Set.of(SupportedCurrency.USD, SupportedCurrency.HTG);
		var key = key();
		checkout(r, "USD", key, 201);
		assertThat(checkout(r, "HTG", key, 409).get("code").asString()).isEqualTo("idempotency_key_reuse");
		assertThat(post("/api/v1/reservations/" + r.reference() + "/payments/checkout-session", Map.of("currency", "USD", "phone", phone(r)), null, null).andExpect(status().isUnprocessableContent()).andReturn()
				.getResponse().getContentAsString()).contains("idempotency_key_required");
	}

	@Test
	void onlyTheOwnerCanOpenOrReadAPaymentAndEveryMissIsTheSame404() throws Exception {
		var r = readyBoth();
		var wrongPhone = post("/api/v1/reservations/" + r.reference() + "/payments/checkout-session", Map.of("currency", "USD", "phone", "9999 9999"), null, key()).andExpect(status().isNotFound()).andReturn()
				.getResponse().getContentAsString();
		var unknown = post("/api/v1/reservations/ANA-ZZZZZZ/payments/checkout-session", Map.of("currency", "USD", "phone", phone(r)), null, key()).andExpect(status().isNotFound()).andReturn().getResponse()
				.getContentAsString();
		assertThat(JSON.readTree(wrongPhone).get("code").asString()).isEqualTo("reservation_not_found");
		assertThat(JSON.readTree(unknown).get("code").asString()).isEqualTo("reservation_not_found");
		assertThat(JSON.readTree(wrongPhone).get("detail")).isEqualTo(JSON.readTree(unknown).get("detail"));
		post("/api/v1/reservations/" + r.reference() + "/payments/status", Map.of("phone", "9999 9999"), null, null).andExpect(status().isNotFound());
		assertThat(gateway.calls.get()).isZero();
	}

	@Test
	void onlineCheckoutOpensOnlyWhenTheReservationIsReady() throws Exception {
		var pending = submit(key(), line(mattress, 1, SupportedCurrency.USD));
		assertThat(checkout(pending, "USD", key(), 409).get("code").asString()).isEqualTo("invalid_status");
		assertThat(gateway.calls.get()).isZero();
	}

	@Test
	void aSettledCurrencyCannotBePaidAgainOnline() throws Exception {
		var r = readyBoth();
		post("/api/v1/admin/reservations/" + r.id() + "/payments", Map.of("amount", "180.00", "currency", "USD"), newStaffToken(), key()).andExpect(status().isCreated());
		assertThat(checkout(r, "USD", key(), 409).get("code").asString()).isEqualTo("already_paid");
	}

	// ---- webhook: signature --------------------------------------------------------------------------------------

	@Test
	void webhooksWithoutAValidSignatureAreRejectedAndChangeNothing() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		var payload = sessionEvent("evt_bad_1", "checkout.session.completed", attempt, "pi_bad", "18000", "usd", "paid");
		assertThat(webhook(payload, null)).isEqualTo(400);
		assertThat(webhook(payload, "t=1,v1=deadbeef")).isEqualTo(400);
		assertThat(webhook(payload + " ", sign(payload))).isEqualTo(400); // signed bytes differ from the body
		assertThat(stripePayments(r)).isZero();
		assertThat(paymentStatus(r).get("reservation").get("paymentStatus").asString()).isEqualTo("UNPAID");
	}

	@Test
	void theWebhookIs404WhenStripeIsOff() throws Exception {
		gateway.enabled = false;
		assertThat(deliver("{\"id\":\"evt_off\",\"type\":\"checkout.session.completed\"}")).isEqualTo(404);
	}

	// ---- webhook: outcomes ---------------------------------------------------------------------------------------

	@Test
	void aVerifiedPaymentIsPostedOnceAndSettlesOnlyItsOwnCurrency() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		var payload = sessionEvent("evt_ok_1", "checkout.session.completed", attempt, "pi_ok_1", "18000", "usd", "paid");
		// returning from Stripe proves nothing: before the webhook the reservation is still unpaid
		assertThat(paymentStatus(r).get("reservation").get("paymentStatus").asString()).isEqualTo("UNPAID");
		assertThat(deliver(payload)).isEqualTo(200);
		assertThat(deliver(payload)).isEqualTo(200); // a duplicate delivery
		assertThat(deliver(sessionEvent("evt_ok_2", "checkout.session.completed", attempt, "pi_ok_1", "18000", "usd", "paid"))).isEqualTo(200); // same payment, another event id
		assertThat(stripePayments(r)).isEqualTo(1);
		var status = paymentStatus(r);
		assertThat(status.get("attempts").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(balance(status, "USD").get("due").asString()).isEqualTo("0.00");
		assertThat(balance(status, "USD").get("paid").asString()).isEqualTo("180.00");
		assertThat(balance(status, "HTG").get("due").asString()).isEqualTo("25000.00"); // the other currency is untouched
		assertThat(status.get("reservation").get("paymentStatus").asString()).isEqualTo("PARTIAL"); // not PAID while HTG is outstanding
		var recon = payments.reconcile(r.id());
		assertThat(recon.lines()).allMatch(l -> l.cachedPaid().equals(l.ledgerPaid()));
		var stored = ledger.payments(r.id()).get(0);
		assertThat(stored.reference()).isEqualTo("pi_ok_1");
		assertThat(stored.recordedBy()).isNull();
	}

	@Test
	void theCustomerSeesTheirPaymentHistoryAndStaffSeeTheMethodSplitAndAConsistentReconciliation() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		assertThat(deliver(sessionEvent("evt_hist_1", "checkout.session.completed", attempt, "pi_hist", "18000", "usd", "paid"))).isEqualTo(200);
		var staff = newStaffToken();
		post("/api/v1/admin/reservations/" + r.id() + "/payments", Map.of("amount", "25000.00", "currency", "HTG"), staff, key()).andExpect(status().isCreated());

		// the customer's own view: both payments, in their own currency, with how they were paid; nothing internal
		var view = JSON.readTree(post("/api/v1/reservations/lookup", Map.of("reference", r.reference(), "phone", phone(r)), null, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(view.get("payments")).hasSize(2);
		var byCurrency = new java.util.HashMap<String, JsonNode>();
		view.get("payments").forEach(p -> byCurrency.put(p.get("currency").asString(), p));
		assertThat(byCurrency.get("USD").get("amount").asString()).isEqualTo("180.00");
		assertThat(byCurrency.get("USD").get("method").asString()).isEqualTo("STRIPE_CHECKOUT");
		assertThat(byCurrency.get("HTG").get("amount").asString()).isEqualTo("25000.00");
		assertThat(byCurrency.get("HTG").get("method").asString()).isEqualTo("CASH_AT_PICKUP");
		assertThat(view.get("paymentStatus").asString()).isEqualTo("PAID");
		var raw = view.get("payments").toString();
		assertThat(raw).doesNotContain("pi_hist").doesNotContain("recordedBy").doesNotContain("reference").doesNotContain("note");
		for (var b : view.get("balances")) {
			assertThat(b.get("due").asString()).isEqualTo("0.00");
		}

		// the reports tell cash and Stripe apart, per currency, never summed across currencies
		var report = JSON.readTree(get("/api/v1/admin/reports/payments", staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		var split = report.get("receivedByMethod");
		assertThat(new java.math.BigDecimal(split.get("STRIPE_CHECKOUT").get("USD").asString())).isGreaterThanOrEqualTo(new java.math.BigDecimal("180.00"));
		assertThat(new java.math.BigDecimal(split.get("STRIPE_CHECKOUT").get("HTG").asString())).isEqualByComparingTo("0");   // this fake account only takes USD: HTG is shown, as zero, never converted
		assertThat(new java.math.BigDecimal(split.get("CASH_AT_PICKUP").get("HTG").asString())).isGreaterThanOrEqualTo(new java.math.BigDecimal("25000.00"));

		// staff reconciliation: the cached paid amounts equal the ledger, per currency, and the Stripe payment is in the list marked as online
		var recon = JSON.readTree(get("/api/v1/admin/reservations/" + r.id() + "/reconciliation", staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(recon.toString()).doesNotContain("\"consistent\":false");
		var adminPayments = JSON.readTree(get("/api/v1/admin/reservations/" + r.id() + "/payments", staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(adminPayments.toString()).contains("STRIPE_CHECKOUT").contains("CASH_AT_PICKUP");
	}

	@Test
	void aMismatchedAmountOrCurrencyPostsNothingAndIsFlagged() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		assertThat(deliver(sessionEvent("evt_mm_1", "checkout.session.completed", attempt, "pi_mm", "100", "usd", "paid"))).isEqualTo(200);
		assertThat(deliver(sessionEvent("evt_mm_2", "checkout.session.completed", attempt, "pi_mm", "18000", "eur", "paid"))).isEqualTo(200);
		assertThat(stripePayments(r)).isZero();
		var staffAttempts = JSON.readTree(get("/api/v1/admin/payment-attempts?requiresAttention=true&reservationId=" + r.id(), newStaffToken()).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString());
		assertThat(staffAttempts.get("items").get(0).get("attentionReason").asString()).isEqualTo("amount_mismatch");
		assertThat(paymentStatus(r).get("reservation").get("paymentStatus").asString()).isEqualTo("UNPAID");
	}

	@Test
	void anAsynchronousPaymentStaysProcessingUntilConfirmed() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		assertThat(deliver(sessionEvent("evt_as_1", "checkout.session.completed", attempt, "pi_as", "18000", "usd", "unpaid"))).isEqualTo(200);
		assertThat(paymentStatus(r).get("attempts").get(0).get("status").asString()).isEqualTo("PROCESSING");
		assertThat(stripePayments(r)).isZero();
		assertThat(checkout(r, "USD", key(), 409).get("code").asString()).isEqualTo("payment_in_progress"); // no second session while one is in flight
		assertThat(deliver(sessionEvent("evt_as_2", "checkout.session.async_payment_succeeded", attempt, "pi_as", "18000", "usd", "paid"))).isEqualTo(200);
		assertThat(stripePayments(r)).isEqualTo(1);
		assertThat(paymentStatus(r).get("attempts").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
	}

	@Test
	void aFailedAsynchronousPaymentMarksNothingPaidAndAllowsANewCheckout() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		deliver(sessionEvent("evt_af_1", "checkout.session.completed", attempt, "pi_af", "18000", "usd", "unpaid"));
		deliver(sessionEvent("evt_af_2", "checkout.session.async_payment_failed", attempt, "pi_af", "18000", "usd", "unpaid"));
		var status = paymentStatus(r);
		assertThat(status.get("attempts").get(0).get("status").asString()).isEqualTo("FAILED");
		assertThat(status.get("reservation").get("paymentStatus").asString()).isEqualTo("UNPAID");
		assertThat(status.get("reservation").get("status").asString()).isEqualTo("READY_FOR_PICKUP"); // the reservation is kept
		assertThat(stripePayments(r)).isZero();
		var again = checkout(r, "USD", key(), 201);
		assertThat(again.get("attemptId").asString()).isNotEqualTo(attempt.get("attemptId").asString());
	}

	@Test
	void anExpiredOrAbandonedSessionKeepsTheReservationAndNeverCancelsIt() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		deliver(sessionEvent("evt_ex_1", "checkout.session.expired", attempt, "pi_ex", "18000", "usd", "unpaid"));
		var status = paymentStatus(r);
		assertThat(status.get("attempts").get(0).get("status").asString()).isEqualTo("EXPIRED");
		assertThat(status.get("reservation").get("status").asString()).isEqualTo("READY_FOR_PICKUP");
		assertThat(stripePayments(r)).isZero();
	}

	@Test
	void eventsInAnyOrderNeverUndoAConfirmedPayment() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		deliver(sessionEvent("evt_oo_1", "checkout.session.completed", attempt, "pi_oo", "18000", "usd", "paid"));
		deliver(sessionEvent("evt_oo_2", "checkout.session.expired", attempt, "pi_oo", "18000", "usd", "paid"));
		deliver(sessionEvent("evt_oo_3", "checkout.session.async_payment_failed", attempt, "pi_oo", "18000", "usd", "paid"));
		assertThat(paymentStatus(r).get("attempts").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(stripePayments(r)).isEqualTo(1);
		// and the opposite order: the failure arrives first, then the success
		var r2 = readyBoth();
		var a2 = checkout(r2, "USD", key(), 201);
		deliver(sessionEvent("evt_oo_4", "checkout.session.async_payment_failed", a2, "pi_oo2", "18000", "usd", "unpaid"));
		deliver(sessionEvent("evt_oo_5", "checkout.session.async_payment_succeeded", a2, "pi_oo2", "18000", "usd", "paid"));
		assertThat(paymentStatus(r2).get("attempts").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(stripePayments(r2)).isEqualTo(1);
	}

	@Test
	void anEventForAnUnknownSessionIsAcknowledgedAndChangesNothing() throws Exception {
		var fake = JSON.readTree("{\"attemptId\":\"" + UUID.randomUUID() + "\"}");
		assertThat(deliver(sessionEvent("evt_un_1", "checkout.session.completed", fake, "pi_un", "18000", "usd", "paid"))).isEqualTo(200);
		assertThat(deliver("{\"id\":\"evt_un_2\",\"object\":\"event\",\"type\":\"customer.created\",\"data\":{\"object\":{}}}")).isEqualTo(200);
	}

	@Test
	void aPaymentThatArrivesAfterCashSettledTheBalanceIsPostedAndFlaggedNotLost() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		post("/api/v1/admin/reservations/" + r.id() + "/payments", Map.of("amount", "180.00", "currency", "USD"), newStaffToken(), key()).andExpect(status().isCreated());
		deliver(sessionEvent("evt_dbl_1", "checkout.session.completed", attempt, "pi_dbl", "18000", "usd", "paid"));
		assertThat(stripePayments(r)).isEqualTo(1); // the customer was really charged: it is on the ledger
		var staffAttempts = JSON.readTree(get("/api/v1/admin/payment-attempts?requiresAttention=true&reservationId=" + r.id(), newStaffToken()).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString());
		assertThat(staffAttempts.get("items").get(0).get("attentionReason").asString()).contains("already_settled");
		assertThat(payments.reconcile(r.id()).lines()).allMatch(l -> l.cachedPaid().equals(l.ledgerPaid()));
	}

	// ---- staff ----------------------------------------------------------------------------------------------------

	@Test
	void staffCannotRecordOrReverseAStripePaymentAndAttemptsAreStaffOnly() throws Exception {
		var r = readyBoth();
		var staffToken = newStaffToken();
		var refused = post("/api/v1/admin/reservations/" + r.id() + "/payments", Map.of("amount", "180.00", "currency", "USD", "method", "STRIPE_CHECKOUT"), staffToken, key()).andExpect(status().isUnprocessableContent())
				.andReturn().getResponse().getContentAsString();
		assertThat(refused).contains("method_not_allowed");
		var attempt = checkout(r, "USD", key(), 201);
		deliver(sessionEvent("evt_st_1", "checkout.session.completed", attempt, "pi_st", "18000", "usd", "paid"));
		var paymentId = ledger.payments(r.id()).get(0).id();
		post("/api/v1/admin/payments/" + paymentId + "/reversal", Map.of("reason", "test"), staffToken, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("online_payment_reversal_unsupported"));
		get("/api/v1/admin/payments?method=STRIPE_CHECKOUT&reservationId=" + r.id(), staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].method").value("STRIPE_CHECKOUT"))
				.andExpect(jsonPath("$.items[0].reference").value("pi_st"));
		get("/api/v1/admin/payment-attempts", null).andExpect(status().isUnauthorized());
		get("/api/v1/admin/payment-attempts", token(UUID.randomUUID())).andExpect(status().isForbidden());
		var list = JSON.readTree(get("/api/v1/admin/payment-attempts?reservationId=" + r.id(), staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(list.get("items").get(0).has("checkoutUrl")).isFalse(); // staff never see checkout URLs
		assertThat(list.get("items").get(0).get("reservationReference").asString()).isEqualTo(r.reference());
	}

	// ---- concurrency ----------------------------------------------------------------------------------------------

	@Test
	void simultaneousDuplicateDeliveriesPostExactlyOnePayment() throws Exception {
		var r = readyBoth();
		var attempt = checkout(r, "USD", key(), 201);
		var payload = sessionEvent("evt_conc_1", "checkout.session.completed", attempt, "pi_conc", "18000", "usd", "paid");
		var signature = sign(payload);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
		var start = new java.util.concurrent.CountDownLatch(1);
		var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < 8; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return webhook(payload, signature);
			}));
		}
		start.countDown();
		for (var f : results) {
			assertThat(f.get()).isEqualTo(200);
		}
		pool.shutdown();
		assertThat(stripePayments(r)).isEqualTo(1);
		assertThat(payments.reconcile(r.id()).lines()).allMatch(l -> l.cachedPaid().equals(l.ledgerPaid()));
		assertThat(paymentStatus(r).get("reservation").get("balances").toString()).contains("\"paid\":\"180.00\"");
	}

	@Test
	void simultaneousCheckoutRequestsShareOneOpenAttempt() throws Exception {
		var r = readyBoth();
		var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
		var start = new java.util.concurrent.CountDownLatch(1);
		var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
		for (int i = 0; i < 6; i++) {
			results.add(pool.submit(() -> {
				start.await();
				var res = post("/api/v1/reservations/" + r.reference() + "/payments/checkout-session", Map.of("currency", "USD", "phone", phone(r)), null, key()).andReturn().getResponse();
				return res.getStatus() + ":" + JSON.readTree(res.getContentAsString()).path("attemptId").asString("");
			}));
		}
		start.countDown();
		var ids = new java.util.HashSet<String>();
		for (var f : results) {
			var v = f.get();
			assertThat(v).matches("20[01]:.+");
			ids.add(v.substring(4));
		}
		pool.shutdown();
		assertThat(ids).hasSize(1);
		assertThat(paymentStatus(r).get("attempts")).hasSize(1);
	}
}
