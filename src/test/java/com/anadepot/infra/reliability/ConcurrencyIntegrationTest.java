package com.anadepot.infra.reliability;

import static com.anadepot.testsupport.TestData.htg;
import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.ItemKey;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real concurrent transactions against PostgreSQL. These prove the strategy (row locks in a stable order + unique keys),
 * not frontend validation.
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

	@Autowired ReservationRepository reservationRepository;
	@Autowired PaymentRepository paymentRepository;

	private record Outcome(Object value, Throwable error) {
		String code() {
			return error instanceof DomainRuleViolation v ? v.code() : error == null ? "ok" : error.getClass().getSimpleName();
		}
	}

	/** Releases all tasks at once so they genuinely overlap. */
	private List<Outcome> race(int threads, List<Callable<Object>> tasks) throws Exception {
		var pool = Executors.newFixedThreadPool(Math.max(threads, tasks.size()));
		var ready = new CountDownLatch(tasks.size());
		var go = new CountDownLatch(1);
		var futures = new ArrayList<Future<Outcome>>();
		for (var task : tasks) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				try {
					return new Outcome(task.call(), null);
				} catch (Throwable t) {
					return new Outcome(null, t);
				}
			}));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		var results = new ArrayList<Outcome>();
		for (var f : futures) {
			results.add(f.get(120, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return results;
	}

	@Test
	void twentyCustomersCompeteForFiveUnits_exactlyFiveSucceedAcrossBothCurrencies() throws Exception {
		var product = stockedMattress(5);
		var tasks = new ArrayList<Callable<Object>>();
		for (int i = 0; i < 20; i++) {
			var currency = i % 2 == 0 ? SupportedCurrency.HTG : SupportedCurrency.USD;
			tasks.add(() -> submit(key(), line(product, 1, currency)));
		}

		var results = race(10, tasks);

		assertThat(results.stream().filter(o -> o.error() == null)).hasSize(5);
		assertThat(results.stream().filter(o -> o.error() != null).map(Outcome::code)).containsOnly("insufficient_stock");
		var item = inventory.get(product.id(), null);
		assertThat(item.reserved()).isEqualTo(5);
		assertThat(item.available()).isZero();
		assertThat(item.onHand()).isEqualTo(5);
		// the stored counter equals the quantity actually held by open reservations
		assertThat(reservationRepository.openHeldQuantity(new ItemKey(product.id(), null))).isEqualTo(5);
	}

	@Test
	void concurrentReservationsOfTwoProductsInOppositeOrderNeverDeadlock() throws Exception {
		var a = stockedMattress(40);
		var b = stockedMattress(40);
		var tasks = new ArrayList<Callable<Object>>();
		for (int i = 0; i < 16; i++) {
			// half list A then B, half B then A: without a stable lock order this would deadlock
			tasks.add(i % 2 == 0
					? () -> submit(key(), line(a, 1, SupportedCurrency.HTG), line(b, 1, SupportedCurrency.USD))
					: () -> submit(key(), line(b, 1, SupportedCurrency.HTG), line(a, 1, SupportedCurrency.USD)));
		}
		var results = race(10, tasks);
		assertThat(results.stream().map(Outcome::code)).containsOnly("ok");
		assertThat(inventory.get(a.id(), null).reserved()).isEqualTo(16);
		assertThat(inventory.get(b.id(), null).reserved()).isEqualTo(16);
	}

	@Test
	void identicalConcurrentSubmissionsWithOneIdempotencyKeyCreateOneReservationAndHoldStockOnce() throws Exception {
		var product = stockedMattress(10);
		var key = key();
		var contact = contact("0000 7777");
		var command = new com.anadepot.core.reservation.service.SubmitReservationCommand(List.of(line(product, 3, SupportedCurrency.HTG)), contact, "fr", PICKUP,
				com.anadepot.core.shared.Actor.guest(), key);
		var tasks = new ArrayList<Callable<Object>>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> reservations.submit(command).id());
		}

		var results = race(8, tasks);

		assertThat(results.stream().map(Outcome::code)).containsOnly("ok");
		assertThat(results.stream().map(Outcome::value).distinct()).hasSize(1);      // every caller got the SAME reservation
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(3);        // stock held once, not 8 times
	}

	@Test
	void concurrentPaymentsWithTheSameKeyApplyOnce() throws Exception {
		var product = stockedMattress(10);
		var r = ready(submit(key(), line(product, 2, SupportedCurrency.HTG))); // 50000 HTG
		var key = key();
		var at = Instant.parse("2026-11-15T10:00:00Z");
		var tasks = new ArrayList<Callable<Object>>();
		for (int i = 0; i < 6; i++) {
			tasks.add(() -> payments.record(new RecordPaymentCommand(r.id(), htg("20000.00"), PaymentMethod.CASH_AT_PICKUP, at, staff, null, null, key)).id());
		}

		var results = race(6, tasks);

		assertThat(results.stream().map(Outcome::code)).containsOnly("ok");
		assertThat(results.stream().map(Outcome::value).distinct()).hasSize(1);
		assertThat(paymentRepository.payments(r.id())).hasSize(1);
		assertThat(reservations.get(r.id()).paid().get(SupportedCurrency.HTG)).isEqualTo(htg("20000.00"));
		assertThat(payments.reconcile(r.id()).consistent()).isTrue();
	}

	@Test
	void twoDifferentFullPaymentsRaceOnOneReservation_theLockLetsExactlyOneThrough() throws Exception {
		var product = stockedMattress(10);
		var r = ready(submit(key(), line(product, 1, SupportedCurrency.HTG))); // 25000 HTG
		var tasks = new ArrayList<Callable<Object>>();
		for (int i = 0; i < 4; i++) {
			tasks.add(() -> payments.record(new RecordPaymentCommand(r.id(), htg("25000.00"), PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key())).id());
		}

		var results = race(4, tasks);

		assertThat(results.stream().filter(o -> o.error() == null)).hasSize(1);
		assertThat(results.stream().filter(o -> o.error() != null).map(Outcome::code)).containsOnly("already_paid");
		assertThat(reservations.get(r.id()).paid().get(SupportedCurrency.HTG)).isEqualTo(htg("25000.00")); // not 100000
	}

	@Test
	void conflictingTransitionsOnOneReservationAreSerialised() throws Exception {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 4, SupportedCurrency.HTG));
		var tasks = new ArrayList<Callable<Object>>();
		tasks.add(() -> reservations.accept(r.id(), staff, null).status());
		tasks.add(() -> reservations.reject(r.id(), staff, RejectReason.CANNOT_FULFILL, null, null).status());
		tasks.add(() -> reservations.cancel(r.id(), staff, "changed mind", null).status());
		tasks.add(() -> reservations.accept(r.id(), staff, null).status());

		var results = race(4, tasks);

		var final_ = reservations.get(r.id());
		// whichever transition won, the others saw the new status and were refused or ran after it consistently
		assertThat(results.stream().filter(o -> o.error() != null).map(Outcome::code)).containsOnly("invalid_status");
		var item = inventory.get(product.id(), null);
		if (final_.status() == ReservationStatus.CONFIRMED) {
			assertThat(item.reserved()).isEqualTo(4);
		} else {
			assertThat(final_.status()).isIn(ReservationStatus.REJECTED, ReservationStatus.CANCELLED);
			assertThat(item.reserved()).isZero(); // released exactly once, never twice (which would fail inventory_inconsistent)
		}
		assertThat(final_.history().size()).isBetween(2, 3);
	}
}
