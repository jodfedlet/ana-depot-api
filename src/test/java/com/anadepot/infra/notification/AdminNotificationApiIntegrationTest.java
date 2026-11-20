package com.anadepot.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff visibility of customer e-mails over HTTP: masked recipients, filters, summary, the audited retry of a message that gave up, and who may do what. */
@org.springframework.context.annotation.Import(NotificationFlowIntegrationTest.ScriptedTransportConfig.class)
class AdminNotificationApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String ADMIN = "/api/v1/admin/notifications";

	@Autowired NotificationFlowIntegrationTest.Scripted transport;
	@Autowired NotificationDispatcher dispatcher;
	@Autowired NotificationJpaRepository jpa;
	@PersistenceContext EntityManager em;

	Product mattress;
	Instant now;

	@BeforeEach
	void setUp() {
		jpa.deleteAllInBatch();
		transport.reset();
		ensurePickupAvailable();
		mattress = stockedMattress(1000);
		now = Instant.now().plusSeconds(120);
	}

	private Reservation submit(String email) {
		return reservations.submit(new SubmitReservationCommand(List.of(line(mattress, 1, SupportedCurrency.HTG)), new Contact("Marie", "Exemple", "+509", "3" + String.format("%07d", (long) (Math.random() * 9_999_999)), email),
				"fr", NotificationFlowIntegrationTest.SNAPSHOT, Actor.guest(), key()));
	}

	private JsonNode json(String path, String bearer) throws Exception {
		return JSON.readTree(get(path, bearer).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private UUID deadNotificationOf(Reservation r) throws Exception {
		transport.failure = m -> new MailFailure("recipient_rejected", true, null);
		dispatcher.dispatchOnce(now);
		transport.failure = m -> null;
		var items = json(ADMIN + "?status=DEAD&reservationId=" + r.id(), newStaffTokenCached()).get("items");
		return UUID.fromString(items.get(0).get("id").asString());
	}

	private String staffToken;

	private String newStaffTokenCached() {
		if (staffToken == null) {
			staffToken = newStaffToken();
		}
		return staffToken;
	}

	@Test
	void onlyStaffSeeAndManageNotifications() throws Exception {
		get(ADMIN, null).andExpect(status().isUnauthorized());
		var customer = token(UUID.randomUUID());
		get(ADMIN, customer).andExpect(status().isForbidden());
		get(ADMIN + "/summary", customer).andExpect(status().isForbidden());
		post(ADMIN + "/" + UUID.randomUUID() + "/retry", null, customer, null).andExpect(status().isForbidden());
		post(ADMIN + "/" + UUID.randomUUID() + "/retry", null, null, null).andExpect(status().isUnauthorized());
	}

	@Test
	void theListShowsTypeReferenceMaskedRecipientStatusAttemptsAndFailureCodeButNeverTheAddress() throws Exception {
		var staff = newStaffTokenCached();
		var r = submit("victim.person@example.com");
		transport.failure = m -> new MailFailure("smtp_unavailable", false, null);
		dispatcher.dispatchOnce(now);
		var body = get(ADMIN + "?reservationId=" + r.id(), staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("victim.person@example.com").doesNotContain("victim.person").contains("v***@example.com");
		var item = JSON.readTree(body).get("items").get(0);
		assertThat(item.get("type").asString()).isEqualTo("RESERVATION_SUBMITTED");
		assertThat(item.get("reservationReference").asString()).isEqualTo(r.reference());
		assertThat(item.get("status").asString()).isEqualTo("RETRY");
		assertThat(item.get("attempts").asInt()).isEqualTo(1);
		assertThat(item.get("lastError").asString()).isEqualTo("smtp_unavailable");
		assertThat(item.get("createdAt").asString()).isNotBlank();
		assertThat(item.get("nextAttemptAt").asString()).isNotBlank();
		assertThat(item.get("sentAt").isNull()).isTrue();
		// nothing about the mail server or the message body is exposed
		assertThat(body).doesNotContain("smtp.", "password", "<html", "Bonjour");
	}

	@Test
	void filtersAndTheSummaryTellTheShopOwnerWhatIsWaitingAndWhatFailed() throws Exception {
		var staff = newStaffTokenCached();
		var ok = submit("ok@example.com");
		var none = submit(null);
		dispatcher.dispatchOnce(now);
		var dead = submit("dead@example.com");
		transport.failure = m -> new MailFailure("recipient_rejected", true, null);
		dispatcher.dispatchOnce(now);
		transport.failure = m -> null;
		var summary = json(ADMIN + "/summary", staff);
		assertThat(summary.get("counts").get("SENT").asInt()).isEqualTo(1);
		assertThat(summary.get("counts").get("DEAD").asInt()).isEqualTo(1);
		assertThat(summary.get("counts").get("SKIPPED_NO_RECIPIENT").asInt()).isEqualTo(1);
		assertThat(summary.get("attention").asInt()).as("one message gave up: staff are told to look at it").isGreaterThanOrEqualTo(1);
		assertThat(json(ADMIN + "?status=EXPIRED", staff).get("totalItems").asInt()).isZero();       // the new outcomes are valid filters
		assertThat(json(ADMIN + "?status=SUPERSEDED", staff).get("totalItems").asInt()).isZero();
		assertThat(summary.get("counts").has("EXPIRED")).isTrue();
		assertThat(summary.get("counts").has("SUPERSEDED")).isTrue();
		assertThat(summary.get("mode").asString()).isEqualTo("LOG");                      // the test profile runs without SMTP: the screen says nothing is delivered
		assertThat(json(ADMIN + "?status=DEAD", staff).get("totalItems").asInt()).isEqualTo(1);
		assertThat(json(ADMIN + "?status=SENT&type=RESERVATION_SUBMITTED", staff).get("totalItems").asInt()).isEqualTo(1);
		assertThat(json(ADMIN + "?reservationId=" + dead.id(), staff).get("items").get(0).get("status").asString()).isEqualTo("DEAD");
		get(ADMIN + "?status=NOPE", staff).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("filter_invalid"));
		assertThat(ok.id()).isNotEqualTo(none.id());
	}

	@Test
	void aDeadMessageCanBeRetriedOnceAndThenGoesOutWhileOthersCannotBeRetried() throws Exception {
		var staff = newStaffTokenCached();
		var r = submit("client@example.com");
		var id = deadNotificationOf(r);
		var retried = JSON.readTree(post(ADMIN + "/" + id + "/retry", null, staff, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(retried.get("status").asString()).isEqualTo("PENDING");
		assertThat(retried.get("attempts").asInt()).isZero();
		assertThat(retried.get("lastError").isNull()).isTrue();
		// retrying twice is refused: the message is already back in the queue
		post(ADMIN + "/" + id + "/retry", null, staff, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("notification_not_retryable"));
		dispatcher.dispatchOnce(now);
		assertThat(transport.sent).hasSize(1);
		assertThat(json(ADMIN + "?reservationId=" + r.id(), staff).get("items").get(0).get("status").asString()).isEqualTo(NotificationStatus.SENT.name());
		// a message that was SENT, skipped or never failed cannot be "retried": no duplicate customer message can be provoked from the screen
		post(ADMIN + "/" + id + "/retry", null, staff, null).andExpect(status().isConflict());
		var skipped = submit(null);
		var skippedId = UUID.fromString(json(ADMIN + "?reservationId=" + skipped.id(), staff).get("items").get(0).get("id").asString());
		post(ADMIN + "/" + skippedId + "/retry", null, staff, null).andExpect(status().isConflict());
		post(ADMIN + "/" + UUID.randomUUID() + "/retry", null, staff, null).andExpect(status().isNotFound());
	}

	@Test
	void theRetryIsAuditedWithoutAddressOrBody() throws Exception {
		var staff = newStaffTokenCached();
		var r = submit("client@example.com");
		var id = deadNotificationOf(r);
		post(ADMIN + "/" + id + "/retry", null, staff, null).andExpect(status().isOk());
		var rows = em.createQuery("select e.action, e.newValues from AuditEventEntity e where e.targetType = 'notification' and e.targetId = :id", Object[].class).setParameter("id", id.toString()).getResultList();
		assertThat(rows).hasSize(1);
		assertThat((String) rows.get(0)[0]).isEqualTo("NOTIFICATION_RETRY_REQUESTED");
		assertThat((String) rows.get(0)[1]).contains("RESERVATION_SUBMITTED").doesNotContain("client@example.com");
	}

	@Test
	void twoStaffMembersRetryingTheSameMessageAtOnceQueueItExactlyOnce() throws Exception {
		var r = submit("client@example.com");
		var id = deadNotificationOf(r);
		var tokens = List.of(newStaffToken(), newStaffToken(), newStaffToken(), newStaffToken());
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(tokens.size());
		var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
		for (var t : tokens) {
			results.add(pool.submit(() -> {
				start.await();
				return mvc.perform(MockMvcRequestBuilders.post(ADMIN + "/" + id + "/retry").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + t)).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		var statuses = new java.util.ArrayList<Integer>();
		for (var f : results) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
		dispatcher.dispatchOnce(now);
		assertThat(transport.sent).hasSize(1);                                              // one retry, one message
		assertThat(NotificationType.valueOf("RESERVATION_SUBMITTED")).isNotNull();
	}
}
