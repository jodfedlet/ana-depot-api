package com.anadepot.infra.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.infra.reservation.ReservationHttpSupport;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Staff customer management over HTTP. Guests and registered customers are separate records and are never merged. */
class AdminCustomerApiIntegrationTest extends ReservationHttpSupport {

	static final String BASE = "/api/v1/admin/customers";
	@Autowired AuditEventJpaRepository audits;

	private String staffToken;
	private Product mattress;

	@BeforeEach
	void setUp() {
		staffToken = newStaffToken();
		ensurePickupAvailable();
		mattress = stockedMattress(100);
	}

	private JsonNode findByPhone(String phone) throws Exception {
		var page = JSON.readTree(get(BASE + "?q=" + phone, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(page.get("totalItems").asInt()).isEqualTo(1);
		return page.get("items").get(0);
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryCustomerEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get(BASE, bearer).andExpect(status().is(expected));
			get(BASE + "/" + id, bearer).andExpect(status().is(expected));
			put(BASE + "/" + id, Map.of("version", 0, "firstName", "A", "lastName", "B"), bearer).andExpect(status().is(expected));
		}
	}

	@Test
	void aGuestReservationCreatesAGuestCustomerStaffCanFindAndSeePerCurrencyFigures() throws Exception {
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 2, "HTG")), null);
		reserve(request(phone, line(mattress, 1, "USD")), null);
		var found = findByPhone(phone);
		assertThat(found.get("kind").asString()).isEqualTo("GUEST");
		assertThat(found.get("hasAccount").asBoolean()).isFalse();
		assertThat(found.get("activity").get("reservations").asInt()).isEqualTo(2);
		assertThat(found.get("activity").get("lastReservationAt").isNull()).isFalse();
		assertThat(found.get("summary").isNull()).isTrue();                              // lists stay light

		var id = found.get("id").asString();
		var detail = JSON.readTree(get(BASE + "/" + id, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(detail.get("summary").get("reservationsByStatus").get("PENDING_REVIEW").asInt()).isEqualTo(2);
		assertThat(detail.get("summary").get("openDueByCurrency").get("HTG").asString()).isEqualTo("50000.00");      // two mattresses at 25000.00
		assertThat(detail.get("summary").get("openDueByCurrency").get("USD").asString()).isEqualTo("180.00");        // never added to the HTG figure
		assertThat(detail.get("summary").get("collectedByCurrency").size()).isZero();
		get("/api/v1/admin/reservations?customerId=" + id, staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound());
	}

	@Test
	void collectedReservationsAddUpPerCurrencyAndLeaveTheOpenFigure() throws Exception {
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		var id = created.get("id").asString();
		post("/api/v1/admin/reservations/" + id + "/accept", Map.of(), staffToken, null).andExpect(status().isOk());
		post("/api/v1/admin/reservations/" + id + "/ready", Map.of(), staffToken, null).andExpect(status().isOk());
		post("/api/v1/admin/reservations/" + id + "/payments", Map.of("amount", "25000.00", "currency", "HTG"), staffToken, "pay-" + UUID.randomUUID()).andExpect(status().isCreated());
		post("/api/v1/admin/reservations/" + id + "/collect", Map.of(), staffToken, null).andExpect(status().isOk());
		var customerId = findByPhone(phone).get("id").asString();
		get(BASE + "/" + customerId, staffToken).andExpect(jsonPath("$.summary.collectedByCurrency.HTG").value("25000.00")).andExpect(jsonPath("$.summary.reservationsByStatus.COLLECTED").value(1))
				.andExpect(jsonPath("$.summary.openDueByCurrency.HTG").doesNotExist());
	}

	@Test
	void aRegisteredCustomerIsASeparateRecordFromTheGuestWithTheSamePhoneAndNothingIsMerged() throws Exception {
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 1, "HTG")), null);                        // guest
		var user = UUID.randomUUID();
		var token = token(user);
		put("/api/v1/me/profile", Map.of("firstName", "Jean", "lastName", "Compte", "dialCode", "+509", "phone", phone, "email", "jean@example.test"), token).andExpect(status().isOk());
		reserve(request(phone, line(mattress, 1, "HTG")), token);                       // signed in: attaches to the account row
		var page = JSON.readTree(get(BASE + "?q=" + phone + "&sort=NAME", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(page.get("totalItems").asInt()).isEqualTo(2);
		var kinds = new java.util.HashSet<String>();
		page.get("items").forEach(c -> kinds.add(c.get("kind").asString()));
		assertThat(kinds).containsExactlyInAnyOrder("GUEST", "REGISTERED");
		get(BASE + "?q=" + phone + "&kind=REGISTERED", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].activity.reservations").value(1));
		get(BASE + "?q=" + phone + "&kind=GUEST", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].activity.reservations").value(1));
		get(BASE + "?kind=NOBODY", staffToken).andExpect(status().isUnprocessableContent());
	}

	@Test
	void staffCorrectAGuestNameAndEmailWithTheVersionAndTheAuditNeverHoldsPersonalData() throws Exception {
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 1, "HTG")), null);
		var found = findByPhone(phone);
		var id = found.get("id").asString();
		var version = found.get("version").asLong();
		put(BASE + "/" + id, Map.of("firstName", "Marie", "lastName", "X"), staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		put(BASE + "/" + id, Map.of("version", version, "firstName", " ", "lastName", "", "email", "nope"), staffToken).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.errors[?(@.field=='firstName')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='lastName')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='email')]").isNotEmpty());
		var secretName = "Corrigé" + UUID.randomUUID().toString().substring(0, 8);
		var updated = JSON.readTree(put(BASE + "/" + id, Map.of("version", version, "firstName", secretName, "lastName", "Exemple", "email", "marie@example.test"), staffToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value(secretName)).andReturn().getResponse().getContentAsString());
		assertThat(updated.get("version").asLong()).isGreaterThan(version);
		assertThat(updated.get("phone").asString()).isEqualTo(found.get("phone").asString());      // identity untouched
		put(BASE + "/" + id, Map.of("version", version, "firstName", "Autre", "lastName", "Exemple"), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_update"));
		var trail = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("customer", id);
		assertThat(trail).extracting(a -> a.getAction()).containsExactly("CUSTOMER_UPDATED");
		var dump = trail.stream().map(a -> a.getPreviousValues() + " " + a.getNewValues() + " " + a.getReason()).collect(java.util.stream.Collectors.joining(" "));
		assertThat(dump).doesNotContain(secretName).doesNotContain("marie@example.test").contains("firstName");
		// past reservations keep the contact they were made with
		get("/api/v1/admin/reservations?customerId=" + id, staffToken).andExpect(status().isOk());
		var reservation = JSON.readTree(get("/api/v1/admin/reservations?customerId=" + id, staffToken).andReturn().getResponse().getContentAsString()).get("items").get(0);
		assertThat(reservation.get("contact").get("firstName").asString()).isEqualTo("Marie");
	}

	@Test
	void aRegisteredCustomerProfileBelongsToItsOwnerAndStaffCannotEditIt() throws Exception {
		var phone = freshPhone();
		var user = UUID.randomUUID();
		put("/api/v1/me/profile", Map.of("firstName", "Jean", "lastName", "Compte", "dialCode", "+509", "phone", phone, "email", "j@example.test"), token(user)).andExpect(status().isOk());
		var registered = findByPhone(phone);
		assertThat(registered.get("kind").asString()).isEqualTo("REGISTERED");
		put(BASE + "/" + registered.get("id").asString(), Map.of("version", registered.get("version").asLong(), "firstName", "Staff", "lastName", "Edit"), staffToken).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("customer_managed_by_owner"));
		get("/api/v1/me/profile", token(user)).andExpect(jsonPath("$.firstName").value("Jean"));
	}

	@Test
	void twoSimultaneousCorrectionsWithTheSameVersionLetExactlyOneWin() throws Exception {
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 1, "HTG")), null);
		var found = findByPhone(phone);
		var id = found.get("id").asString();
		int threads = 4;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(threads);
		var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < threads; i++) {
			var name = "Nom" + i;
			results.add(pool.submit(() -> {
				start.await();
				return put(BASE + "/" + id, Map.of("version", found.get("version").asLong(), "firstName", name, "lastName", "Exemple"), staffToken).andReturn().getResponse().getStatus();
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
	}

	@Test
	void listSearchesNamesEmailAndPhoneAndFiltersByPeriodAndPaginates() throws Exception {
		var phone = freshPhone();
		reserve(request(phone, line(mattress, 1, "HTG")), null);
		get(BASE + "?q=Marie+Exemple", staffToken).andExpect(status().isOk());
		get(BASE + "?q=" + phone + "&from=2000-01-01&to=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(1));
		get(BASE + "?q=" + phone + "&from=2999-01-01", staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?from=2999-01-01&to=2000-01-01", staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("date_range_invalid"));
		get(BASE + "?q=%25_%5C", staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?size=1&page=0", staffToken).andExpect(jsonPath("$.items.length()").value(1));
		get(BASE + "?size=1000", staffToken).andExpect(status().isUnprocessableContent());
		var detailSupportedCurrencies = SupportedCurrency.values().length;
		assertThat(detailSupportedCurrencies).isEqualTo(2);
	}
}
