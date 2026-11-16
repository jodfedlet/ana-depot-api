package com.anadepot.infra.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Guest lookup by reference + phone: access control, enumeration resistance and what a customer may see. */
class ReservationLookupApiIntegrationTest extends ReservationHttpSupport {

	@Autowired ReservationRepository reservationRepository;

	@BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	private tools.jackson.databind.JsonNode lookup(String reference, String phone) throws Exception {
		return body(post("/api/v1/reservations/lookup", Map.of("reference", reference, "phone", phone), null, null).andExpect(status().isOk()).andReturn());
	}

	@Test
	void thePhoneNumberUnlocksTheReservationWithOrWithoutTheDialCodeAndWhateverTheFormatting() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();                                    // 8 digits, e.g. 31234567
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		var ref = created.get("reference").asString();
		var spaced = phone.substring(0, 4) + " " + phone.substring(4);

		for (var typed : new String[] {phone, spaced, "+509 " + spaced, "509" + phone, "00509" + phone, "(509) " + phone}) {
			assertThat(lookup(ref, typed).get("id").asString()).as(typed).isEqualTo(created.get("id").asString());
		}
		assertThat(lookup(" " + ref.toLowerCase() + " ", phone).get("reference").asString()).isEqualTo(ref);   // reference is case/space tolerant
	}

	@Test
	void aReferenceAloneIsNeverEnough_andAWrongOrPartialPhoneFindsNothing() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var ref = reserve(request(phone, line(mattress, 1, "HTG")), null).get("reference").asString();

		for (var wrong : new String[] {"31111111", phone.substring(2), phone.substring(3), "123456", "+1 " + phone, "0" + phone.substring(1)}) {
			post("/api/v1/reservations/lookup", Map.of("reference", ref, "phone", wrong), null, null).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("reservation_not_found"));
		}
	}

	@Test
	void unknownReferenceMalformedReferenceAndWrongPhoneAreIndistinguishable() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var ref = reserve(request(phone, line(mattress, 1, "HTG")), null).get("reference").asString();

		var wrongPhone = stable(post("/api/v1/reservations/lookup", Map.of("reference", ref, "phone", "30000000"), null, null).andReturn());
		var unknownReference = stable(post("/api/v1/reservations/lookup", Map.of("reference", "ANA-ZZZZZZ", "phone", phone), null, null).andReturn());
		var malformed = stable(post("/api/v1/reservations/lookup", Map.of("reference", "not a reference", "phone", phone), null, null).andReturn());
		var tooShortPhone = stable(post("/api/v1/reservations/lookup", Map.of("reference", ref, "phone", "123"), null, null).andReturn());

		assertThat(wrongPhone).startsWith("404 ");
		assertThat(unknownReference).isEqualTo(wrongPhone);
		assertThat(malformed).isEqualTo(wrongPhone);
		assertThat(tooShortPhone).isEqualTo(wrongPhone);
		assertThat(wrongPhone).doesNotContain(ref).doesNotContain(phone);
	}

	@Test
	void missingFieldsAreValidationErrors() throws Exception {
		post("/api/v1/reservations/lookup", Map.of("reference", "ANA-ABCDEF"), null, null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.errors[0].field").value("phone")).andExpect(jsonPath("$.errors[0].code").value("required"));
		post("/api/v1/reservations/lookup", Map.of(), null, null).andExpect(status().isUnprocessableContent());
	}

	@Test
	void theCustomerSeesTheProgressButNeverInternalNotesStaffIdentitiesOrStaffWrittenReasons() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 2, "HTG"), line(mattress, 1, "USD")), null);
		var id = UUID.fromString(created.get("id").asString());
		var ref = created.get("reference").asString();

		reservations.accept(id, staff, null);
		var r = reservationRepository.findById(id).orElseThrow();
		r.addNote(NoteVisibility.INTERNAL, "INTERNAL-SECRET-NOTE do not call this customer", staff, Instant.now());
		r.addNote(NoteVisibility.CUSTOMER_VISIBLE, "Votre commande est en preparation", staff, Instant.now());
		reservationRepository.save(r);
		reservations.cancel(id, staff, "STAFF-FREE-TEXT-REASON customer is a fraud", null);

		var result = post("/api/v1/reservations/lookup", Map.of("reference", ref, "phone", phone), null, null).andExpect(status().isOk()).andReturn();
		var text = result.getResponse().getContentAsString();
		assertThat(text).doesNotContain("INTERNAL-SECRET-NOTE", "STAFF-FREE-TEXT-REASON", staff.userId().toString(), "userId", "actor", "authUserId", "customerId");

		var view = body(result);
		var types = new java.util.ArrayList<String>();
		view.get("events").forEach(e -> types.add(e.get("type").asString()));
		assertThat(types).containsExactly("SUBMITTED", "ACCEPTED", "NOTE", "CANCELLED");
		assertThat(view.get("events").get(2).get("note").asString()).isEqualTo("Votre commande est en preparation");
		assertThat(view.get("events").get(3).get("cancelledBy").asString()).isEqualTo("STORE");
		assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(view.get("totals")).hasSize(2);
	}

	@Test
	void aRejectionShowsTheReasonCodeOnly() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		reservations.reject(UUID.fromString(created.get("id").asString()), staff, RejectReason.OTHER, "STAFF-EXPLANATION-TEXT", null);

		var result = post("/api/v1/reservations/lookup", Map.of("reference", created.get("reference").asString(), "phone", phone), null, null).andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("STAFF-EXPLANATION-TEXT");
		var last = body(result).get("events").get(1);
		assertThat(last.get("type").asString()).isEqualTo("REJECTED");
		assertThat(last.get("rejectReason").asString()).isEqualTo("OTHER");
	}

	@Test
	void thereIsNoCustomerCancelEndpoint() throws Exception {
		var mattress = stockedMattress(5);
		var created = reserve(request(freshPhone(), line(mattress, 1, "HTG")), null);
		post("/api/v1/reservations/" + created.get("id").asString() + "/cancel", Map.of(), null, key()).andExpect(status().isUnauthorized());
		get("/api/v1/reservations/" + created.get("id").asString(), null).andExpect(status().isUnauthorized());
	}

	@Test
	void phoneMatchingIsDigitsOnlyAndNeverMatchesTooShortInput() {
		var contact = new com.anadepot.core.reservation.model.Contact("A", "B", "+509", "3700 0000", null);
		assertThat(contact.phoneMatches("37000000")).isTrue();
		assertThat(contact.phoneMatches("+509 3700-0000")).isTrue();
		assertThat(contact.phoneMatches("00509 37000000")).isTrue();
		assertThat(contact.phoneMatches("000000")).isFalse();
		assertThat(contact.phoneMatches("700000")).isFalse();     // a suffix is not a match
		assertThat(contact.phoneMatches("")).isFalse();
		assertThat(contact.phoneMatches(null)).isFalse();
	}
}
