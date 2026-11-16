package com.anadepot.infra.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.reservation.ReservationHttpSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Customer accounts over HTTP: own data only, registered customers are separate from guests, nothing is merged. */
class AccountApiIntegrationTest extends ReservationHttpSupport {

	@BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	private static Map<String, Object> profile(String phone) {
		var m = new LinkedHashMap<String, Object>();
		m.put("firstName", "Jean");
		m.put("lastName", "Baptiste");
		m.put("dialCode", "+509");
		m.put("phone", phone);
		m.put("email", "jean@example.test");
		return m;
	}

	@Test
	void everyAccountEndpointNeedsASignedInUser() throws Exception {
		get("/api/v1/me/profile", null).andExpect(status().isUnauthorized());
		put("/api/v1/me/profile", profile("37000000"), null).andExpect(status().isUnauthorized());
		get("/api/v1/me/reservations", null).andExpect(status().isUnauthorized());
		get("/api/v1/me/reservations/" + UUID.randomUUID(), null).andExpect(status().isUnauthorized());
	}

	@Test
	void theProfileIsCreatedOnFirstSaveAndUpdatedInPlaceAfterwards() throws Exception {
		var user = UUID.randomUUID();
		var token = token(user);
		get("/api/v1/me/profile", token).andExpect(status().isOk()).andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.email").value(user + "@example.test"));          // nothing saved yet: only the account e-mail

		var first = body(put("/api/v1/me/profile", profile("3700 0001"), token).andExpect(status().isOk()).andReturn());
		assertThat(first.get("id").asString()).isNotBlank();
		assertThat(first.get("phone").asString()).isEqualTo("37000001");

		var changed = profile("37000002");
		changed.put("firstName", "Jeanne");
		var second = body(put("/api/v1/me/profile", changed, token).andExpect(status().isOk()).andReturn());
		assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
		get("/api/v1/me/profile", token).andExpect(jsonPath("$.firstName").value("Jeanne")).andExpect(jsonPath("$.phone").value("37000002"));
		get("/api/v1/me", token).andExpect(jsonPath("$.customerProfileId").value(first.get("id").asString()));
	}

	@Test
	void profileValidationReportsEveryField() throws Exception {
		var token = token(UUID.randomUUID());
		var bad = new LinkedHashMap<String, Object>();
		bad.put("firstName", "");
		bad.put("lastName", "B");
		bad.put("dialCode", "+509");
		bad.put("phone", "12");
		bad.put("email", "not-an-email");
		put("/api/v1/me/profile", bad, token).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("validation_failed"))
				.andExpect(jsonPath("$.errors.length()").value(3));
	}

	@Test
	void aSignedInReservationBelongsToTheirOwnCustomerRecordAndIsListedOnlyForThem() throws Exception {
		var mattress = stockedMattress(20);
		var alice = token(UUID.randomUUID());
		var bob = token(UUID.randomUUID());
		var phone = freshPhone();

		var mine = reserve(request(phone, line(mattress, 1, "HTG")), alice);
		var mineToo = reserve(request(phone, line(mattress, 1, "USD")), alice);
		var bobs = reserve(request(phone, line(mattress, 1, "HTG")), bob);          // SAME phone number, different account

		var list = body(get("/api/v1/me/reservations", alice).andExpect(status().isOk()).andReturn());
		assertThat(list.get("totalItems").asInt()).isEqualTo(2);
		assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(mineToo.get("id").asString());   // newest first
		assertThat(list.get("items").get(1).get("id").asString()).isEqualTo(mine.get("id").asString());
		get("/api/v1/me/reservations/" + mine.get("id").asString(), alice).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(mine.get("reference").asString()));

		var bobList = body(get("/api/v1/me/reservations", bob).andReturn());
		assertThat(bobList.get("totalItems").asInt()).isEqualTo(1);
		assertThat(bobList.get("items").get(0).get("id").asString()).isEqualTo(bobs.get("id").asString());
	}

	@Test
	void anotherCustomersReservationIsExactlyLikeAMissingOne() throws Exception {
		var mattress = stockedMattress(5);
		var alice = token(UUID.randomUUID());
		var bob = token(UUID.randomUUID());
		var aliceRes = reserve(request(freshPhone(), line(mattress, 1, "HTG")), alice);

		var others = get("/api/v1/me/reservations/" + aliceRes.get("id").asString(), bob).andExpect(status().isNotFound()).andReturn();
		var missing = get("/api/v1/me/reservations/" + UUID.randomUUID(), bob).andExpect(status().isNotFound()).andReturn();
		assertThat(stable(others).replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "ID")).isEqualTo(stable(missing).replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "ID"));
		assertThat(others.getResponse().getContentAsString()).doesNotContain(aliceRes.get("reference").asString());
	}

	@Test
	void aGuestReservationIsNotMergedIntoAnAccountEvenWithTheSamePhoneAndTheSameEmail() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var guest = reserve(request(phone, line(mattress, 1, "HTG")), null);
		var user = token(UUID.randomUUID());
		put("/api/v1/me/profile", profile(phone), user).andExpect(status().isOk());

		get("/api/v1/me/reservations", user).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
		get("/api/v1/me/reservations/" + guest.get("id").asString(), user).andExpect(status().isNotFound());
		// the guest can still follow their reservation with reference + phone
		post("/api/v1/reservations/lookup", Map.of("reference", guest.get("reference").asString(), "phone", phone), null, null).andExpect(status().isOk());

		// and a LATER guest reservation with the same phone still goes to the guest record, never to the account
		reserve(request(phone, line(mattress, 1, "HTG")), null);
		get("/api/v1/me/reservations", user).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void savingTheProfileNeverChangesExistingReservationsContactSnapshot() throws Exception {
		var mattress = stockedMattress(5);
		var user = token(UUID.randomUUID());
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), user);
		put("/api/v1/me/profile", profile("39999999"), user).andExpect(status().isOk());
		get("/api/v1/me/reservations/" + created.get("id").asString(), user).andExpect(jsonPath("$.contact.phone").value(phone))
				.andExpect(jsonPath("$.contact.firstName").value("Marie"));
	}

	@Test
	void pagingIsBounded() throws Exception {
		var user = token(UUID.randomUUID());
		get("/api/v1/me/reservations?size=1000", user).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_page"));
		get("/api/v1/me/reservations?page=-1", user).andExpect(status().isUnprocessableContent());
		get("/api/v1/me/reservations?page=0&size=5", user).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}
}
