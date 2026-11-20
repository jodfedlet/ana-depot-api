package com.anadepot.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationRequest;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.notification.service.NotificationRecorder;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The notification workflow over the real PostgreSQL, the real use cases and the real dispatcher; only the mail transport is scripted (the real SMTP behaviour is in
 * {@link SmtpMailTransportTest}). Time is driven explicitly: {@code dispatchOnce(now)}.
 */
@Import(NotificationFlowIntegrationTest.ScriptedTransportConfig.class)
class NotificationFlowIntegrationTest extends AbstractIntegrationTest {

	static final class Scripted implements MailTransport {
		final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
		final Map<UUID, Integer> perNotification = new ConcurrentHashMap<>();
		volatile Function<OutgoingMail, MailFailure> failure = m -> null;
		volatile boolean delivers = true;

		@Override
		public boolean delivers() {
			return delivers;
		}

		@Override
		public void send(OutgoingMail mail) {
			var f = failure.apply(mail);
			if (f != null) {
				throw f;
			}
			sent.add(mail);
			perNotification.merge(mail.notificationId(), 1, Integer::sum);
		}

		void reset() {
			sent.clear();
			perNotification.clear();
			failure = m -> null;
			delivers = true;
		}
	}

	@TestConfiguration
	static class ScriptedTransportConfig {
		@Bean
		@Primary
		Scripted scriptedTransport() {
			return new Scripted();
		}
	}

	static final String SNAPSHOT = """
			{"location":{"storeName":"Magasin Snapshot","addressLine":"1 rue de l'Essai","city":"Port-au-Prince","phone":"+509 0000 0000","email":"magasin@example.test",
			"schedule":[{"day":1,"open":true,"from":"09:00","to":"17:00"},{"day":7,"open":false,"from":"","to":""}]},
			"instructions":{"general":{"fr":"Apportez votre référence.","ht":"Pote referans ou."},"ready":{"fr":"Entrez par la porte principale.","ht":"Antre pa gran pòt la."}}}""";

	@Autowired Scripted transport;
	@Autowired NotificationDispatcher dispatcher;
	@Autowired NotificationRepository notifications;
	@Autowired NotificationRecorder recorder;
	@Autowired NotificationJpaRepository jpa;
	@Autowired com.anadepot.core.reservation.service.ReservationAdminService adminReservations;
	@Autowired com.anadepot.core.notification.service.NotificationAdminService adminNotifications;

	Product mattress;
	Instant t0;

	@BeforeEach
	void setUp() {
		jpa.deleteAllInBatch();                   // other test classes leave waiting rows behind: this class only looks at its own
		transport.reset();
		ensurePickupAvailable();
		mattress = stockedMattress(10_000);
		t0 = Instant.now().plusSeconds(120);       // "now" for the dispatcher: later than every row this test creates (rows become due at creation time)
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	private static String phone() {
		return "3" + String.format("%07d", (long) (Math.random() * 9_999_999));
	}

	private Reservation submitFor(String email, String locale, String firstName, SubmitReservationCommand.Line... lines) {
		return reservations.submit(new SubmitReservationCommand(List.of(lines), new Contact(firstName, "Exemple", "+509", phone(), email), locale, SNAPSHOT, Actor.guest(), key()));
	}

	private Reservation mixed(String email) {
		return submitFor(email, "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG), line(mattress, 1, SupportedCurrency.USD));
	}

	private List<Notification> rowsOf(Reservation r) {
		return notifications.list(new NotificationQuery(null, null, r.id()), new PageRequest(0, 100)).items();
	}

	private Notification row(Reservation r, NotificationType type) {
		return rowsOf(r).stream().filter(n -> n.type() == type).findFirst().orElseThrow(() -> new AssertionError("no " + type + " for " + r.reference()));
	}

	private void drain(Instant now) {
		for (int i = 0; i < 50; i++) {
			if (dispatcher.dispatchOnce(now) == 0) {
				return;
			}
		}
	}

	private List<OutgoingMail> mailsOf(Reservation r) {
		return transport.sent.stream().filter(m -> m.subject().contains(r.reference())).toList();
	}

	private OutgoingMail lastMailOf(Reservation r) {
		var list = mailsOf(r);
		return list.get(list.size() - 1);
	}

	private void cash(Reservation r, SupportedCurrency currency) {
		var due = reservations.get(r.id()).balances().get(currency).due();
		payments.record(new RecordPaymentCommand(r.id(), due, PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
	}

	// ---- every event, correct recipient, correct content -----------------------------------------------------------

	@Test
	void everyBusinessEventOfAReservationProducesOneCorrectMessageToTheRightAddress() {
		var r = mixed("client@example.com");
		assertThat(rowsOf(r)).extracting(Notification::type).containsExactly(NotificationType.RESERVATION_SUBMITTED);
		drain(t0);
		assertThat(mailsOf(r)).hasSize(1);
		var submitted = lastMailOf(r);
		assertThat(submitted.to()).isEqualTo("client@example.com");
		assertThat(submitted.subject()).isEqualTo("Réservation " + r.reference() + " reçue");
		// both currencies, each total on its own, never added; the quantity and unit of each line; the pickup details exactly as snapshotted
		assertThat(submitted.html()).contains("25 000,00 HTG", "180,00 USD", "Magasin Snapshot", "1 rue de l&#39;Essai", "Lundi : 09:00 – 17:00", "Dimanche : Fermé").doesNotContain("25 180");
		assertThat(submitted.html()).contains(r.reference()).contains("Bonjour Marie,");
		assertThat(submitted.text()).contains(r.reference(), "25 000,00 HTG", "180,00 USD");

		reservations.accept(r.id(), staff, null);
		drain(t0);
		assertThat(lastMailOf(r).subject()).isEqualTo("Réservation " + r.reference() + " acceptée");
		assertThat(lastMailOf(r).html()).contains("25 000,00 HTG", "180,00 USD", "Reste à payer");

		reservations.markReady(r.id(), staff, null, null);
		drain(t0);
		var ready = lastMailOf(r);
		assertThat(ready.subject()).isEqualTo("Réservation " + r.reference() + " prête à retirer");
		assertThat(ready.html()).contains("Magasin Snapshot", "Entrez par la porte principale.", "25 000,00 HTG", "180,00 USD", "Vous payez au magasin lors du retrait.");  // no card: Stripe is not enabled in this test

		cash(r, SupportedCurrency.HTG);
		drain(t0);
		var receipt = lastMailOf(r);
		assertThat(receipt.subject()).isEqualTo("Paiement reçu pour la réservation " + r.reference());
		assertThat(receipt.html()).contains("25 000,00 HTG", "Espèces au magasin", "Réglé", "180,00 USD").doesNotContain("Carte en ligne");   // HTG settled, USD still due: independent balances

		cash(r, SupportedCurrency.USD);
		drain(t0);
		reservations.collect(r.id(), staff, null);
		drain(t0);
		var collected = lastMailOf(r);
		assertThat(collected.subject()).isEqualTo("Réservation " + r.reference() + " retirée");
		assertThat(collected.html()).contains("25 000,00 HTG", "180,00 USD", "Espèces au magasin");
		assertThat(mailsOf(r)).hasSize(6);   // submitted, confirmed, ready, 2 receipts, collected
		assertThat(rowsOf(r)).allMatch(n -> n.status() == NotificationStatus.SENT && n.sentAt() != null && n.attempts() == 1);
		assertThat(transport.perNotification.values()).allMatch(c -> c == 1);
	}

	@Test
	void aVerifiedCardPaymentGetsAReceiptThatSaysCardNeverCash() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		drain(t0);
		transport.reset();
		var due = reservations.get(r.id()).balances().get(SupportedCurrency.USD).due();
		payments.recordProviderPayment(r.id(), due, "pi_receipt_test");            // exactly what a verified provider event posts
		drain(t0);
		var receipt = lastMailOf(r);
		assertThat(receipt.subject()).isEqualTo("Paiement reçu pour la réservation " + r.reference());
		assertThat(receipt.html()).contains("180,00\u00a0USD", "Carte en ligne", "25\u00a0000,00\u00a0HTG").doesNotContain("Espèces au magasin");   // USD paid by card; HTG still due, shown on its own
		assertThat(receipt.html() + receipt.text()).doesNotContain("pi_receipt_test");                     // no provider reference or card detail is ever mailed
	}

	@Test
	void noReceiptIsQueuedForMoneyThatWasNeverRecorded() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		drain(t0);
		assertThat(rowsOf(r)).extracting(Notification::type).doesNotContain(NotificationType.PAYMENT_RECEIVED, NotificationType.PAYMENT_FAILED, NotificationType.CHECKOUT_EXPIRED);
	}

	@Test
	void rejectionCancellationAndExpiryCarryNoInternalOrStaffText() {
		var rejected = mixed("client@example.com");
		reservations.reject(rejected.id(), staff, RejectReason.PRODUCT_UNAVAILABLE, "INTERNAL-NOTE-SECRET staff-only reason", null);
		var cancelled = mixed("client@example.com");
		reservations.accept(cancelled.id(), staff, null);
		reservations.cancel(cancelled.id(), staff, "INTERNAL-FRAUD-SUSPICION do not tell the customer", null);
		var expired = mixed("client@example.com");
		reservations.accept(expired.id(), staff, null);
		reservations.expire(expired.id(), Actor.system(), null);
		drain(t0);
		for (var r : List.of(rejected, cancelled, expired)) {
			for (var m : mailsOf(r)) {
				assertThat(m.html() + m.text() + m.subject()).doesNotContain("INTERNAL", "secret", "staff-only", "FRAUD", "suspicion");
			}
		}
		assertThat(lastMailOf(rejected).subject()).isEqualTo("Réservation " + rejected.reference() + " non acceptée");
		assertThat(lastMailOf(rejected).html()).contains("Un ou plusieurs produits ne sont plus disponibles.");
		assertThat(lastMailOf(cancelled).subject()).isEqualTo("Réservation " + cancelled.reference() + " annulée");
		assertThat(lastMailOf(cancelled).html()).contains("Aucun paiement n’est enregistré").doesNotContain("remboursement");
		assertThat(lastMailOf(expired).subject()).isEqualTo("Réservation " + expired.reference() + " expirée");
		assertThat(lastMailOf(expired).html()).contains("a expiré");
	}

	@Test
	void cancellationAndCollectionShowTheRealPaymentsAndNeverClaimARefund() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		cash(r, SupportedCurrency.HTG);
		reservations.cancel(r.id(), staff, "internal", null);
		drain(t0);
		var m = lastMailOf(r);
		assertThat(m.subject()).contains("annulée");
		assertThat(m.html()).contains("Paiements enregistrés", "25 000,00 HTG", "Espèces au magasin", "contactez le magasin à leur sujet");
		assertThat(m.html() + m.text()).doesNotContain("remboursé", "Remboursement effectué");
	}

	@Test
	void internalAndCustomerNotesAreNeverMailed() {
		var r = mixed("client@example.com");
		drain(t0);
		int before = mailsOf(r).size();
		adminReservations.addNote(r.id(), com.anadepot.core.reservation.model.NoteVisibility.INTERNAL, "internal remark never for the customer", staff, key());
		adminReservations.addNote(r.id(), com.anadepot.core.reservation.model.NoteVisibility.CUSTOMER_VISIBLE, "visible on the tracking page only", staff, key());
		drain(t0);
		assertThat(mailsOf(r)).hasSize(before);
		assertThat(rowsOf(r)).hasSize(1);
	}

	@Test
	void aHaitianCreoleReservationGetsHaitianCreoleMessages() {
		var r = submitFor("client@example.com", "ht", "Évelyne", line(mattress, 1, SupportedCurrency.HTG));
		drain(t0);
		var m = lastMailOf(r);
		assertThat(m.subject()).isEqualTo("Rezèvasyon " + r.reference() + " resevwa");
		assertThat(m.html()).contains("Bonjou Évelyne,", "Nou byen resevwa rezèvasyon w lan", "Magasin Snapshot", "Antre").doesNotContain("Bonjour", "Nous avons bien reçu");
		assertThat(m.text()).contains("Nou byen resevwa", "Évelyne");
	}

	@Test
	void theMessageShowsThePickupAsPromisedAtReservationNotTheStoresLaterSettings() {
		var r = submitFor("client@example.com", "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG));
		var current = storeSettings.getPickup();
		storeSettings.savePickup(com.anadepot.testsupport.TestData.testPickup(true), current.version(), staff);   // the store changes its settings afterwards
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		drain(t0);
		var ready = lastMailOf(r);
		assertThat(ready.html()).contains("Magasin Snapshot").doesNotContain("0 Test Street");
	}

	@Test
	void customerTextIsEscapedEndToEnd() {
		var r = submitFor("client@example.com", "fr", "\"><img src=x onerror=alert(1)>", line(mattress, 1, SupportedCurrency.HTG));
		drain(t0);
		var m = lastMailOf(r);
		assertThat(m.html()).doesNotContain("<img src=x", "onerror=alert(1)>").contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
	}

	// ---- recipients ---------------------------------------------------------------------------------------------

	@Test
	void aMissingOrInvalidAddressIsSkippedVisiblyAndNothingIsSent() {
		var none = submitFor(null, "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG));
		// the domain already refuses a malformed address at reservation time ("E-mail looks invalid"); the recorder is a second line of defence for anything that gets past it
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> submitFor("not-an-address", "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG)))).hasMessageContaining("E-mail looks invalid");
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_CONFIRMED, none.id(), "fr", "not-an-address"));
		drain(t0);
		assertThat(row(none, NotificationType.RESERVATION_SUBMITTED).status()).isEqualTo(NotificationStatus.SKIPPED_NO_RECIPIENT);
		assertThat(row(none, NotificationType.RESERVATION_CONFIRMED).status()).isEqualTo(NotificationStatus.SKIPPED_INVALID_RECIPIENT);
		assertThat(transport.sent).isEmpty();
	}

	@Test
	void oneAddressCannotBeFloodedThroughTheShop() {
		var statuses = new ArrayList<NotificationStatus>();
		for (int i = 0; i < 12; i++) {
			var r = submitFor("victim@example.com", "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG));
			statuses.add(row(r, NotificationType.RESERVATION_SUBMITTED).status());
		}
		assertThat(statuses.stream().filter(s -> s == NotificationStatus.PENDING).count()).isEqualTo(10);
		assertThat(statuses.stream().filter(s -> s == NotificationStatus.SKIPPED_THROTTLED).count()).isEqualTo(2);
	}

	// ---- duplicates --------------------------------------------------------------------------------------------

	@Test
	void duplicateEventsAndRepeatedProcessingSendExactlyOneMessage() {
		var r = mixed("client@example.com");
		for (int i = 0; i < 5; i++) {
			recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, r.id(), "fr", "client@example.com"));   // the same business fact, delivered five more times
		}
		assertThat(rowsOf(r)).hasSize(1);
		for (int i = 0; i < 4; i++) {
			drain(t0.plusSeconds(i));
		}
		assertThat(mailsOf(r)).hasSize(1);
		assertThat(row(r, NotificationType.RESERVATION_SUBMITTED).attempts()).isEqualTo(1);
	}

	@Test
	void aWorkerThatDiedLeavesALeaseAndTheMessageIsSentOnceByTheNextWorker() {
		var r = mixed("client@example.com");
		// a worker claims the row and "crashes" before sending or settling
		var claimed = notifications.claimDue(t0, 10, t0.plus(Duration.ofMinutes(2)));
		assertThat(claimed).extracting(Notification::reservationId).contains(r.id());
		assertThat(dispatcher.dispatchOnce(t0.plusSeconds(30))).isZero();                     // the lease is still valid: nobody else takes it
		assertThat(transport.sent).isEmpty();
		assertThat(dispatcher.dispatchOnce(t0.plus(Duration.ofMinutes(3)))).isEqualTo(1);     // lease expired: another worker sends it
		assertThat(mailsOf(r)).hasSize(1);
		assertThat(row(r, NotificationType.RESERVATION_SUBMITTED).status()).isEqualTo(NotificationStatus.SENT);
	}

	@Test
	void concurrentWorkersNeverSendTheSameMessageTwice() throws Exception {
		var made = new ArrayList<Reservation>();
		for (int i = 0; i < 24; i++) {
			made.add(submitFor("c" + i + "@example.com", "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG)));
		}
		int workers = 8;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(workers);
		var futures = new ArrayList<java.util.concurrent.Future<?>>();
		for (int w = 0; w < workers; w++) {
			futures.add(pool.submit(() -> {
				start.await();
				for (int round = 0; round < 6; round++) {
					dispatcher.dispatchOnce(t0);
				}
				return null;
			}));
		}
		start.countDown();
		for (var f : futures) {
			f.get();
		}
		pool.shutdown();
		for (var r : made) {
			assertThat(mailsOf(r)).as(r.reference()).hasSize(1);
		}
		assertThat(transport.perNotification.values()).hasSize(24).allMatch(c -> c == 1);
		assertThat(made).allMatch(r -> row(r, NotificationType.RESERVATION_SUBMITTED).status() == NotificationStatus.SENT);
	}

	// ---- failures, retry, backoff, dead letter ---------------------------------------------------------------------

	@Test
	void anSmtpOutageRetriesWithGrowingJitteredBackoffAndThenSucceedsWithoutLosingTheMessage() {
		var r = mixed("client@example.com");
		transport.failure = m -> new MailFailure("smtp_unavailable", false, null);
		dispatcher.dispatchOnce(t0);
		var first = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(first.status()).isEqualTo(NotificationStatus.RETRY);
		assertThat(first.attempts()).isEqualTo(1);
		assertThat(first.lastErrorCode()).isEqualTo("smtp_unavailable");
		assertThat(first.nextAttemptAt()).isBetween(t0.plusSeconds(30), t0.plusSeconds(60));          // 1 minute, jittered to [30 s, 60 s]
		assertThat(dispatcher.dispatchOnce(t0.plusSeconds(10))).isZero();                              // not due yet

		dispatcher.dispatchOnce(first.nextAttemptAt());
		var second = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(second.attempts()).isEqualTo(2);
		assertThat(second.nextAttemptAt()).isBetween(first.nextAttemptAt().plusSeconds(60), first.nextAttemptAt().plusSeconds(120));   // doubled: [60 s, 120 s]

		transport.failure = m -> null;                                                                 // the server is back
		dispatcher.dispatchOnce(second.nextAttemptAt());
		var done = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(done.status()).isEqualTo(NotificationStatus.SENT);
		assertThat(done.attempts()).isEqualTo(3);
		assertThat(done.lastErrorCode()).isNull();
		assertThat(mailsOf(r)).hasSize(1);
	}

	@Test
	void authenticationFailuresAndTimeoutsAreRetriedAndRecordedAsCodesOnly() {
		var r = mixed("client@example.com");
		transport.failure = m -> new MailFailure("smtp_auth_failed", false, new RuntimeException("535 5.7.8 password TOP-SECRET rejected for client@example.com"));
		dispatcher.dispatchOnce(t0);
		assertThat(row(r, NotificationType.RESERVATION_SUBMITTED).lastErrorCode()).isEqualTo("smtp_auth_failed");
		transport.failure = m -> new MailFailure("smtp_timeout", false, null);
		dispatcher.dispatchOnce(row(r, NotificationType.RESERVATION_SUBMITTED).nextAttemptAt());
		var n = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(n.lastErrorCode()).isEqualTo("smtp_timeout");
		assertThat(n.status()).isEqualTo(NotificationStatus.RETRY);
		assertThat(n.toString()).doesNotContain("TOP-SECRET").doesNotContain("client@example.com");
	}

	@Test
	void aPermanentFailureGoesStraightToDeadAndAnExhaustedBudgetGoesDeadAfterTheLastAttempt() {
		var permanent = mixed("rejected@example.com");
		transport.failure = m -> new MailFailure("recipient_rejected", true, null);
		dispatcher.dispatchOnce(t0);
		var dead = row(permanent, NotificationType.RESERVATION_SUBMITTED);
		assertThat(dead.status()).isEqualTo(NotificationStatus.DEAD);
		assertThat(dead.attempts()).isEqualTo(1);
		assertThat(dead.lastErrorCode()).isEqualTo("recipient_rejected");
		assertThat(dispatcher.dispatchOnce(t0.plus(Duration.ofDays(1)))).isZero();                   // a DEAD message is never picked up again by itself

		jpa.deleteAllInBatch();
		var transientOnly = mixed("down@example.com");
		transport.failure = m -> new MailFailure("smtp_unavailable", false, null);
		var now = t0;
		for (int attempt = 1; attempt <= 8; attempt++) {
			dispatcher.dispatchOnce(now);
			var n = row(transientOnly, NotificationType.RESERVATION_SUBMITTED);
			assertThat(n.attempts()).isEqualTo(attempt);
			assertThat(n.status()).isEqualTo(attempt < 8 ? NotificationStatus.RETRY : NotificationStatus.DEAD);
			now = n.nextAttemptAt();
		}
	}

	@Test
	void aMessageTooOldToBeTrueIsNotSent_andIsExpiredNotFailed() {
		var r = mixed("client@example.com");
		dispatcher.dispatchOnce(t0.plus(Duration.ofHours(73)));
		var n = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(n.status()).isEqualTo(NotificationStatus.EXPIRED);   // not DEAD: nothing failed
		assertThat(n.lastErrorCode()).isEqualTo("stale");
		assertThat(transport.sent).isEmpty();
		assertThat(notifications.attentionCount()).as("an informational message that expired needs nobody's attention").isZero();
	}

	@Test
	void aMessageAboutAStateTheReservationLeftIsNeverSent() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		cash(r, SupportedCurrency.HTG);
		cash(r, SupportedCurrency.USD);
		reservations.collect(r.id(), staff, null);            // collected before the dispatcher ever ran
		drain(t0);
		assertThat(row(r, NotificationType.RESERVATION_READY_FOR_PICKUP).status()).isEqualTo(NotificationStatus.SUPERSEDED);   // not DEAD: deliberately not sent, nothing failed
		assertThat(row(r, NotificationType.RESERVATION_READY_FOR_PICKUP).lastErrorCode()).isEqualTo("state_changed");
		assertThat(notifications.attentionCount()).as("a superseded message is not a failure").isZero();
		assertThat(mailsOf(r).stream().map(OutgoingMail::subject)).noneMatch(s -> s.contains("prête à retirer"));
		assertThat(mailsOf(r).stream().map(OutgoingMail::subject)).anyMatch(s -> s.contains("retirée"));
	}

	@Test
	void importantMessagesKeepALongerLimitWhileInformationalOnesStillExpire() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		dispatcher.dispatchOnce(t0.plus(Duration.ofDays(5)));   // mail was down for five days
		assertThat(row(r, NotificationType.RESERVATION_SUBMITTED).status()).as("informational: 72 h").isEqualTo(NotificationStatus.EXPIRED);
		assertThat(row(r, NotificationType.RESERVATION_CONFIRMED).status()).isEqualTo(NotificationStatus.SENT);
		assertThat(row(r, NotificationType.RESERVATION_READY_FOR_PICKUP).status()).as("important: 14 days, and the reservation is still ready").isEqualTo(NotificationStatus.SENT);
		assertThat(mailsOf(r).stream().map(OutgoingMail::subject)).anyMatch(subject -> subject.contains("prête à retirer"));
		assertThat(notifications.attentionCount()).isZero();
	}

	@Test
	void anImportantMessageThatExpiredIsFlaggedToStaffAndCanBeResentWithANewAge() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		dispatcher.dispatchOnce(t0.plus(Duration.ofDays(15)));   // beyond even the important limit
		var ready = row(r, NotificationType.RESERVATION_READY_FOR_PICKUP);
		assertThat(ready.status()).isEqualTo(NotificationStatus.EXPIRED);
		assertThat(notifications.attentionCount()).as("the customer may never have been told").isEqualTo(2);   // confirmed + ready
		assertThat(transport.sent).isEmpty();

		var resent = adminNotifications.retry(ready.id(), staff);
		assertThat(resent.status()).isEqualTo(NotificationStatus.PENDING);
		assertThat(resent.revivedAt()).isNotNull();
		assertThat(resent.createdAt()).isEqualTo(ready.createdAt());   // history is kept; only the age clock restarts
		dispatcher.dispatchOnce(Instant.now().plusSeconds(30));      // the reservation is still ready: the message is still true
		assertThat(row(r, NotificationType.RESERVATION_READY_FOR_PICKUP).status()).isEqualTo(NotificationStatus.SENT);
		assertThat(mailsOf(r).stream().map(OutgoingMail::subject)).anyMatch(subject -> subject.contains("prête à retirer"));
		assertThat(notifications.attentionCount()).isEqualTo(1);        // the confirmation is still unsent and still flagged
	}

	@Test
	void aSupersededMessageCannotBeResentBecauseItWouldNotBeTrue() {
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		cash(r, SupportedCurrency.HTG);
		cash(r, SupportedCurrency.USD);
		reservations.collect(r.id(), staff, null);
		drain(t0);
		var ready = row(r, NotificationType.RESERVATION_READY_FOR_PICKUP);
		assertThat(ready.status()).isEqualTo(NotificationStatus.SUPERSEDED);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminNotifications.retry(ready.id(), staff)).extracting(e -> ((com.anadepot.core.shared.DomainRuleViolation) e).code()).isEqualTo("notification_not_retryable");
	}

	@Test
	void onlyWhatAPublicActionCanCauseIsThrottled_staffTriggeredMessagesAreNeverDropped() {
		var victim = "flood@example.com";
		var last = (Reservation) null;
		for (int i = 0; i < 11; i++) {
			last = submitFor(victim, "fr", "Marie", line(mattress, 1, SupportedCurrency.HTG));
		}
		assertThat(row(last, NotificationType.RESERVATION_SUBMITTED).status()).as("the 11th form submission in an hour is held back").isEqualTo(NotificationStatus.SKIPPED_THROTTLED);
		reservations.accept(last.id(), staff, null);
		reservations.markReady(last.id(), staff, null, null);
		assertThat(row(last, NotificationType.RESERVATION_CONFIRMED).status()).as("a decision by the store is never lost to the throttle").isEqualTo(NotificationStatus.PENDING);
		assertThat(row(last, NotificationType.RESERVATION_READY_FOR_PICKUP).status()).isEqualTo(NotificationStatus.PENDING);
	}

	@Test
	void localCaptureNeverClaimsADelivery() {
		transport.delivers = false;
		var r = mixed("client@example.com");
		drain(t0);
		var n = row(r, NotificationType.RESERVATION_SUBMITTED);
		assertThat(n.status()).isEqualTo(NotificationStatus.CAPTURED);
		assertThat(n.sentAt()).isNull();
	}

	@Test
	void aFailureToSendNeverRollsBackTheBusinessOperation() {
		transport.failure = m -> new MailFailure("smtp_unavailable", false, null);
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		reservations.markReady(r.id(), staff, null, null);
		cash(r, SupportedCurrency.HTG);
		cash(r, SupportedCurrency.USD);
		reservations.collect(r.id(), staff, null);
		drain(t0);                                                                                      // every attempt fails
		var after = reservations.get(r.id());
		assertThat(after.status()).isEqualTo(ReservationStatus.COLLECTED);
		assertThat(after.balances().values()).allMatch(b -> !b.due().isPositive());
		assertThat(payments.reconcile(r.id()).lines()).allMatch(l -> l.cachedPaid().equals(l.ledgerPaid()));
		assertThat(rowsOf(r)).isNotEmpty().allMatch(n -> n.status() == NotificationStatus.RETRY || n.status() == NotificationStatus.DEAD || n.status() == NotificationStatus.SUPERSEDED);   // waiting or deliberately superseded, never silently lost
		// the mail server comes back: the queued messages that are still true go out
		transport.failure = m -> null;
		drain(t0.plus(Duration.ofHours(1)));
		assertThat(mailsOf(r).stream().map(OutgoingMail::subject)).anyMatch(s -> s.contains("retirée"));
	}

	@Test
	void sendingNeverHappensInsideABusinessTransaction() {
		// the business operations returned (they ran with the transport never called) before any dispatch happened
		var r = mixed("client@example.com");
		reservations.accept(r.id(), staff, null);
		assertThat(transport.sent).isEmpty();
		assertThat(rowsOf(r)).extracting(Notification::status).containsOnly(NotificationStatus.PENDING);
		assertThat(Money.of("1.00", SupportedCurrency.HTG).currency()).isEqualTo(SupportedCurrency.HTG);
	}
}
