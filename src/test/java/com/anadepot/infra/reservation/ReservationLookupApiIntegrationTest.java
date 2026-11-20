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
	void thereIsNoCancelByIdEndpoint_customerCancellationNeedsReferenceAndPhone() throws Exception {   // D7: see theCustomerCancels... below for the approved flow
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

	// ---- D7 (owner-approved): the customer cancels until the store starts preparing the order -----------------------------------------------------

	@Autowired com.anadepot.core.notification.repository.NotificationRepository notificationRepository;
	@Autowired com.anadepot.infra.persistence.AuditEventJpaRepository auditEvents;

	private tools.jackson.databind.JsonNode cancel(String reference, String phone, String reason, int expected) throws Exception {
		var payload = new java.util.LinkedHashMap<String, Object>();
		payload.put("reference", reference);
		payload.put("phone", phone);
		if (reason != null) {
			payload.put("reason", reason);
		}
		return body(post("/api/v1/reservations/cancel", payload, null, null).andExpect(status().is(expected)).andReturn());
	}

	private void asStaff(String action, String id) throws Exception {
		post("/api/v1/admin/reservations/" + id + "/" + action, action.equals("ready") ? Map.of() : null, newStaffToken(), key()).andExpect(status().isOk());
	}

	private java.util.List<com.anadepot.core.notification.model.Notification> messages(String id, com.anadepot.core.notification.model.NotificationType type) {
		return notificationRepository.list(new com.anadepot.core.notification.model.NotificationQuery(null, type, UUID.fromString(id)), new com.anadepot.core.shared.PageRequest(0, 20)).items();
	}

	@Test
	void aCustomerCancelsAPendingOrConfirmedReservation_stockIsFreedTheStoreSeesWhoAndWhy_andTheyAreTold() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var pending = reserve(request(phone, line(mattress, 3, "HTG")), null);
		assertThat(lookup(pending.get("reference").asString(), phone).get("canCancel").asBoolean()).as("the backend says the customer may still cancel").isTrue();
		assertThat(inventory.get(mattress.id(), null).reserved()).isEqualTo(3);

		var cancelled = cancel(pending.get("reference").asString(), phone, "J'ai trouvé moins cher ailleurs", 200);
		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(cancelled.get("canCancel").asBoolean()).isFalse();
		assertThat(inventory.get(mattress.id(), null).reserved()).as("the held stock is released at once").isZero();
		assertThat(messages(pending.get("id").asString(), com.anadepot.core.notification.model.NotificationType.RESERVATION_CANCELLED_BY_CUSTOMER)).hasSize(1);
		assertThat(messages(pending.get("id").asString(), com.anadepot.core.notification.model.NotificationType.RESERVATION_CANCELLED)).as("not the store's cancellation message").isEmpty();

		// the store sees WHO cancelled and why (internal), the customer view never carries internal words
		var staff = newStaffToken();
		var detail = body(get("/api/v1/admin/reservations/" + pending.get("id").asString(), staff).andExpect(status().isOk()).andReturn());
		var last = detail.get("history").get(detail.get("history").size() - 1);
		assertThat(last.get("to").asString()).isEqualTo("CANCELLED");
		assertThat(last.get("reasonCode").asString()).isEqualTo("CUSTOMER_REQUEST");
		assertThat(last.get("reasonNote").asString()).isEqualTo("J'ai trouvé moins cher ailleurs");
		assertThat(last.get("actor").get("kind").asString()).isEqualTo("CUSTOMER");

		// a retry (a double click, a lost response) changes nothing and queues no second message
		var again = cancel(pending.get("reference").asString(), phone, "J'ai trouvé moins cher ailleurs", 200);
		assertThat(again.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(messages(pending.get("id").asString(), com.anadepot.core.notification.model.NotificationType.RESERVATION_CANCELLED_BY_CUSTOMER)).hasSize(1);

		// a confirmed reservation too
		var confirmed = reserve(request(freshPhone(), line(mattress, 2, "HTG")), null);
		asStaff("accept", confirmed.get("id").asString());
		var confirmedPhone = detail(confirmed.get("id").asString()).get("contact").get("phone").asString();
		assertThat(cancel(confirmed.get("reference").asString(), confirmedPhone, "Plus besoin", 200).get("status").asString()).isEqualTo("CANCELLED");
		assertThat(inventory.get(mattress.id(), null).reserved()).isZero();
	}

	private tools.jackson.databind.JsonNode detail(String id) throws Exception {
		return body(get("/api/v1/admin/reservations/" + id, newStaffToken()).andExpect(status().isOk()).andReturn());
	}

	@Test
	void cancellationNeedsTheSameProofAsALookup_andEveryMissIsTheSame404() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		var ref = created.get("reference").asString();
		var wrongPhone = stable(post("/api/v1/reservations/cancel", Map.of("reference", ref, "phone", "30000000", "reason", "x"), null, null).andReturn());
		var unknown = stable(post("/api/v1/reservations/cancel", Map.of("reference", "ANA-ZZZZZZ", "phone", phone, "reason", "x"), null, null).andReturn());
		var malformed = stable(post("/api/v1/reservations/cancel", Map.of("reference", "not a reference", "phone", phone, "reason", "x"), null, null).andReturn());
		assertThat(wrongPhone).startsWith("404 ");
		assertThat(unknown).isEqualTo(wrongPhone);
		assertThat(malformed).isEqualTo(wrongPhone);
		assertThat(lookup(ref, phone).get("status").asString()).as("nothing was cancelled by the failed attempts").isEqualTo("PENDING_REVIEW");
		assertThat(inventory.get(mattress.id(), null).reserved()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void aReasonIsRequiredAndBounded_andTheStoreOnceItIsPreparingTheOrderTakesOver() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		var ref = created.get("reference").asString();
		post("/api/v1/reservations/cancel", Map.of("reference", ref, "phone", phone), null, null).andExpect(status().isUnprocessableContent());          // no reason
		post("/api/v1/reservations/cancel", Map.of("reference", ref, "phone", phone, "reason", "   "), null, null).andExpect(status().isUnprocessableContent());
		post("/api/v1/reservations/cancel", Map.of("reference", ref, "phone", phone, "reason", "x".repeat(301)), null, null).andExpect(status().isUnprocessableContent());
		assertThat(lookup(ref, phone).get("status").asString()).isEqualTo("PENDING_REVIEW");

		asStaff("accept", created.get("id").asString());
		assertThat(lookup(ref, phone).get("canCancel").asBoolean()).as("confirmed: still allowed").isTrue();
		asStaff("ready", created.get("id").asString());
		assertThat(lookup(ref, phone).get("canCancel").asBoolean()).as("ready: the customer contacts the store").isFalse();
		post("/api/v1/reservations/cancel", Map.of("reference", ref, "phone", phone, "reason", "Je change d'avis"), null, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("cancellation_not_allowed"));
		assertThat(lookup(ref, phone).get("status").asString()).as("ready stays ready").isEqualTo("READY_FOR_PICKUP");
	}

	@Test
	void theAuditTrailKeepsOnlyTheLengthOfTheCustomersReason() throws Exception {
		var mattress = stockedMattress(10);
		var phone = freshPhone();
		var created = reserve(request(phone, line(mattress, 1, "HTG")), null);
		cancel(created.get("reference").asString(), phone, "Mon motif très personnel", 200);
		var events = auditEvents.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", created.get("id").asString());
		assertThat(events).extracting(e -> e.getAction()).contains("RESERVATION_CANCELLED_BY_CUSTOMER");
		var everything = events.stream().map(e -> e.getAction() + "|" + e.getReason() + "|" + e.getNewValues()).reduce("", String::concat);
		assertThat(everything).contains("customer reason (24 characters)").doesNotContain("très personnel");
	}
}
