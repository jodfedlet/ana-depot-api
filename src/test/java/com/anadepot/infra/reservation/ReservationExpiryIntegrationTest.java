package com.anadepot.infra.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D6 (owner-approved): a ready reservation is held for pickup (the store's setting, else three days), the customer is reminded 24 h before the deadline, and after the deadline the system
 * expires it: stock is released, the customer is told, money already received stays on the ledger.
 */
class ReservationExpiryIntegrationTest extends AbstractIntegrationTest {

	@Autowired NotificationRepository notifications;
	@Autowired com.anadepot.core.reservation.repository.ReservationRepository reservationRepository;

	@BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	private void withHold(Integer days) {
		var s = storeSettings.getPickup();
		storeSettings.savePickup(new com.anadepot.core.settings.model.PickupSettings(s.enabled(), s.unavailableMessage(), s.location(), s.instructions(), days, s.version(), null, null), s.version(), staff);
	}

	private Reservation confirmed(com.anadepot.core.catalog.model.Product product, int quantity) {
		var r = submit(key(), line(product, quantity, SupportedCurrency.HTG));
		return reservations.accept(r.id(), staff, null);
	}

	private List<Notification> messages(Reservation r, NotificationType type) {
		return notifications.list(new NotificationQuery(null, type, r.id()), new PageRequest(0, 20)).items();
	}

	@Test
	void theDeadlineIsReadyTimePlusTheStoresHoldPeriodOrTheApprovedThreeDays() {
		var product = stockedMattress(20);
		var before = Instant.now();
		var ready = reservations.markReady(confirmed(product, 1).id(), staff, null, null);
		assertThat(ready.expiresAt()).isBetween(before.plus(Duration.ofDays(3)).minusSeconds(5), Instant.now().plus(Duration.ofDays(3)).plusSeconds(5));

		withHold(5);                                                                           // the store's own number wins
		var later = Instant.now();
		var fiveDays = reservations.markReady(confirmed(product, 1).id(), staff, null, null);
		assertThat(fiveDays.expiresAt()).isBetween(later.plus(Duration.ofDays(5)).minusSeconds(5), Instant.now().plus(Duration.ofDays(5)).plusSeconds(5));
		assertThat(reservationRepository.findById(ready.id()).orElseThrow().expiresAt()).as("an existing reservation keeps its own deadline when the setting changes").isEqualTo(ready.expiresAt());
		withHold(null);
		var explicit = Instant.now().plus(Duration.ofHours(7));
		assertThat(reservations.markReady(confirmed(product, 1).id(), staff, explicit, null).expiresAt()).as("staff can still name the deadline").isCloseTo(explicit, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
	}

	@Test
	void aReservationPastItsDeadlineExpiresReleasesItsStockAndTellsTheCustomer_onceOnly() {
		var product = stockedMattress(10);
		var r = confirmed(product, 4);
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(4);
		reservations.markReady(r.id(), staff, Instant.now().minusSeconds(1), null);   // the deadline has already passed

		assertThat(reservations.expireOverdue()).isGreaterThanOrEqualTo(1);
		var after = reservations.get(r.id());
		assertThat(after.status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(after.history().get(after.history().size() - 1).actor().kind()).isEqualTo(com.anadepot.core.shared.ActorKind.SYSTEM);
		assertThat(inventory.get(product.id(), null).reserved()).as("the held stock is back on the shelf").isZero();
		assertThat(inventory.get(product.id(), null).onHand()).isEqualTo(10);
		assertThat(messages(r, NotificationType.RESERVATION_EXPIRED)).hasSize(1);

		assertThat(reservations.expireOverdue()).as("a second pass finds nothing for it").isZero();
		assertThat(messages(r, NotificationType.RESERVATION_EXPIRED)).hasSize(1);
	}

	@Test
	void moneyAlreadyReceivedStaysOnTheLedgerWhenAReservationExpires() {
		var product = stockedMattress(10);
		var r = confirmed(product, 1);
		reservations.markReady(r.id(), staff, Instant.now().minusSeconds(1), null);
		payments.record(new RecordPaymentCommand(r.id(), TestData.htg("1000.00"), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
		reservations.expireOverdue();
		assertThat(reservations.get(r.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(payments.reconcile(r.id()).lines()).allMatch(l -> l.cachedPaid().equals(l.ledgerPaid()));
		assertThat(reservations.get(r.id()).paid().get(SupportedCurrency.HTG)).isEqualTo(TestData.htg("1000.00"));
	}

	@Test
	void nothingElseExpires_notPendingNotConfirmedNotCollectedNotAReadyOneBeforeItsDeadline() {
		var product = stockedMattress(10);
		var pending = submit(key(), line(product, 1, SupportedCurrency.HTG));
		var confirmedOnly = confirmed(product, 1);
		var readyLater = reservations.markReady(confirmed(product, 1).id(), staff, Instant.now().plus(Duration.ofDays(2)), null);
		reservations.expireOverdue();
		assertThat(reservations.get(pending.id()).status()).isEqualTo(ReservationStatus.PENDING_REVIEW);
		assertThat(reservations.get(confirmedOnly.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reservations.get(readyLater.id()).status()).isEqualTo(ReservationStatus.READY_FOR_PICKUP);
	}

	@Test
	void theCustomerIsRemindedOnceInsideTheLeadTime_neverEarlyNeverTwiceNeverAfterCollection() {
		var product = stockedMattress(10);
		var early = reservations.markReady(confirmed(product, 1).id(), staff, Instant.now().plus(Duration.ofDays(2)), null);
		var soon = reservations.markReady(confirmed(product, 1).id(), staff, Instant.now().plus(Duration.ofHours(12)), null);
		var collectedSoon = reservations.markReady(confirmed(product, 1).id(), staff, Instant.now().plus(Duration.ofHours(10)), null);
		payments.record(new RecordPaymentCommand(collectedSoon.id(), TestData.htg("25000.00"), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
		reservations.collect(collectedSoon.id(), staff, null);

		assertThat(reservations.remindExpiring()).as("other tests of this class may have ready reservations near their deadline too").isGreaterThanOrEqualTo(1);
		assertThat(messages(soon, NotificationType.RESERVATION_EXPIRING)).hasSize(1);
		assertThat(messages(early, NotificationType.RESERVATION_EXPIRING)).as("more than 24 h to go").isEmpty();
		assertThat(messages(collectedSoon, NotificationType.RESERVATION_EXPIRING)).as("collected: nothing to remind").isEmpty();
		assertThat(reservations.remindExpiring()).as("once per reservation").isZero();
		assertThat(messages(soon, NotificationType.RESERVATION_EXPIRING)).hasSize(1);
	}

	@Test
	void aReminderIsNotQueuedForAReservationThatAlreadyPassedItsDeadline() {
		var product = stockedMattress(10);
		var late = reservations.markReady(confirmed(product, 1).id(), staff, Instant.now().minusSeconds(30), null);
		reservations.remindExpiring();
		assertThat(messages(late, NotificationType.RESERVATION_EXPIRING)).isEmpty();
	}
}
