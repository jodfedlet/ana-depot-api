package com.anadepot.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.resilience.PermanentFailure;
import com.anadepot.infra.resilience.TransientFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = {
		"ana.outbox.initial-backoff=PT0.05S", "ana.outbox.max-backoff=PT0.2S", "ana.outbox.max-attempts=3",
		"ana.outbox.lease-duration=PT1S", "ana.outbox.batch-size=500"})
@Import(OutboxIntegrationTest.ScriptedHandlers.class)
class OutboxIntegrationTest extends AbstractIntegrationTest {

	/** Consumer whose behaviour per aggregate is scripted by the tests (fail N times, then succeed). */
	static class ScriptedHandler implements OutboxEventHandler {
		final Map<UUID, Deque<RuntimeException>> failures = new ConcurrentHashMap<>();
		final Map<UUID, AtomicInteger> successesByEvent = new ConcurrentHashMap<>();
		final Map<UUID, AtomicInteger> invocationsByAggregate = new ConcurrentHashMap<>();
		final Map<UUID, List<Long>> successOrderByAggregate = new ConcurrentHashMap<>();

		@Override
		public String name() {
			return "scripted";
		}

		@Override
		public boolean supports(String eventType) {
			return eventType.startsWith("test.");
		}

		@Override
		public void handle(OutboxMessage m) {
			invocationsByAggregate.computeIfAbsent(m.aggregateId(), k -> new AtomicInteger()).incrementAndGet();
			var script = failures.get(m.aggregateId());
			var next = script == null ? null : script.pollFirst();
			if (next != null) {
				throw next;
			}
			successesByEvent.computeIfAbsent(m.eventId(), k -> new AtomicInteger()).incrementAndGet();
			successOrderByAggregate.computeIfAbsent(m.aggregateId(), k -> new CopyOnWriteArrayList<>()).add(m.aggregateVersion());
		}
	}

	@TestConfiguration
	static class ScriptedHandlers {
		@Bean
		ScriptedHandler scriptedHandler() {
			return new ScriptedHandler();
		}
	}

	@Autowired OutboxJpaRepository outbox;
	@Autowired OutboxDispatcher dispatcher;
	@Autowired OutboxEventRecorder recorder;
	@Autowired ScriptedHandler handler;
	@Autowired EventDeduplicator deduplicator;
	@Autowired MeterRegistry metrics;
	@Autowired PlatformTransactionManager transactionManager;

	private OutboxEventEntity publish(UUID aggregate, long version) {
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> recorder.record(new DomainEvent("test.happened", "test_aggregate", aggregate, version, Map.of("n", version))));
		return outbox.findByAggregateIdOrderBySeq(aggregate).stream().filter(e -> e.aggregateVersion == version).findFirst().orElseThrow();
	}

	private OutboxEventEntity reload(UUID eventId) {
		return outbox.findById(eventId).orElseThrow();
	}

	private void drain(int cycles) throws InterruptedException {
		for (int i = 0; i < cycles; i++) {
			dispatcher.dispatchOnce();
			Thread.sleep(80);
		}
	}

	// ------------------------------------------------------------------ atomic recording

	@Test
	void theEventIsStoredInTheSameTransactionAsTheBusinessChange_andRolledBackWithIt() {
		var product = stockedMattress(1);
		long before = outbox.count();
		assertThatThrownBy(() -> submit(key(), line(product, 5, SupportedCurrency.HTG))).isInstanceOf(DomainRuleViolation.class); // fails: no stock
		assertThat(outbox.count()).isEqualTo(before);                                   // no phantom event for a change that did not happen

		var r = submit(key(), line(product, 1, SupportedCurrency.HTG));
		var events = outbox.findByAggregateIdOrderBySeq(r.id());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).eventType).isEqualTo("reservation.submitted");
		assertThat(events.get(0).aggregateVersion).isEqualTo(1);
		assertThat(events.get(0).status).isEqualTo("PENDING");
		assertThat(events.get(0).payload).contains(r.reference()).doesNotContain("Marie").doesNotContain("0000"); // no personal data in events

		reservations.reject(r.id(), staff, RejectReason.PRODUCT_UNAVAILABLE, null, null);
		assertThat(outbox.findByAggregateIdOrderBySeq(r.id())).extracting(e -> e.eventType).containsExactly("reservation.submitted", "reservation.rejected");
	}

	@Test
	void recordingOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> recorder.record(new DomainEvent("test.x", "test_aggregate", UUID.randomUUID(), 1, Map.of())))
				.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
	}

	// ------------------------------------------------------------------ delivery

	@Test
	void aDueEventIsDeliveredAndMarkedProcessed() throws Exception {
		var aggregate = UUID.randomUUID();
		var event = publish(aggregate, 1);

		drain(2);

		var row = reload(event.id);
		assertThat(row.status).isEqualTo("PROCESSED");
		assertThat(row.processedAt).isNotNull();
		assertThat(row.attempts).isEqualTo(1);
		assertThat(handler.successesByEvent.get(event.id)).hasValue(1);
	}

	@Test
	void aRedeliveredEventDoesNotRunItsEffectTwice() throws Exception {
		var event = publish(UUID.randomUUID(), 1);
		drain(2);
		assertThat(handler.successesByEvent.get(event.id)).hasValue(1);

		// the acknowledgement was "lost": the row goes back to pending and is delivered again (at-least-once)
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			var e = outbox.findById(event.id).orElseThrow();
			e.status = "PENDING";
			e.nextAttemptAt = Instant.now();
		});
		drain(2);

		assertThat(reload(event.id).status).isEqualTo("PROCESSED");
		assertThat(handler.successesByEvent.get(event.id)).hasValue(1);          // the effect ran ONCE
		assertThat(deduplicator.duplicateCount("scripted", event.id.toString())).isEqualTo(1);
	}

	// ------------------------------------------------------------------ retries

	@Test
	void transientFailuresAreRetriedWithBackoffUntilTheyPass() throws Exception {
		var aggregate = UUID.randomUUID();
		handler.failures.put(aggregate, new ConcurrentLinkedDeque<>(List.of(new TransientFailure("503", null), new TransientFailure("503", null))));
		var event = publish(aggregate, 1);

		dispatcher.dispatchOnce();
		var afterFirst = reload(event.id);
		assertThat(afterFirst.status).isEqualTo("PENDING");
		assertThat(afterFirst.attempts).isEqualTo(1);
		assertThat(afterFirst.lastError).contains("TransientFailure");
		assertThat(afterFirst.nextAttemptAt).isAfter(afterFirst.createdAt);              // a delay was scheduled
		assertThat(handler.successesByEvent.get(event.id)).isNull();

		drain(12);

		var row = reload(event.id);
		assertThat(row.status).isEqualTo("PROCESSED");
		assertThat(row.attempts).isEqualTo(3);
		assertThat(row.lastError).isNull();
		assertThat(handler.invocationsByAggregate.get(aggregate)).hasValue(3);
		assertThat(handler.successesByEvent.get(event.id)).hasValue(1);
	}

	@Test
	void aPermanentFailureGoesStraightToTheDeadLetterState() throws Exception {
		var aggregate = UUID.randomUUID();
		handler.failures.put(aggregate, new ConcurrentLinkedDeque<>(List.of(new PermanentFailure("400 bad payload"))));
		var event = publish(aggregate, 1);
		double deadBefore = metrics.get("anadepot.outbox.dead").gauge().value();

		drain(3);

		var row = reload(event.id);
		assertThat(row.status).isEqualTo("DEAD");
		assertThat(row.attempts).isEqualTo(1);                                          // never retried
		assertThat(row.lastError).contains("PermanentFailure").contains("400 bad payload");
		assertThat(metrics.get("anadepot.outbox.dead").gauge().value()).isGreaterThan(deadBefore); // operators can see it
		assertThat(handler.invocationsByAggregate.get(aggregate)).hasValue(1);
	}

	@Test
	void retriesAreBoundedThenTheEventIsDeadLettered() throws Exception {
		var aggregate = UUID.randomUUID();
		var always = new ConcurrentLinkedDeque<RuntimeException>();
		for (int i = 0; i < 10; i++) {
			always.add(new TransientFailure("still down", null));
		}
		handler.failures.put(aggregate, always);
		var event = publish(aggregate, 1);

		drain(15);

		var row = reload(event.id);
		assertThat(row.status).isEqualTo("DEAD");
		assertThat(row.attempts).isEqualTo(3);                                          // ana.outbox.max-attempts
		assertThat(handler.invocationsByAggregate.get(aggregate)).hasValue(3);
		assertThat(row.lastError).contains("still down");
	}

	// ------------------------------------------------------------------ recovery & ordering & concurrency

	@Test
	void anEventClaimedByACrashedDispatcherIsRecoveredOnceItsLeaseExpires() throws Exception {
		var event = publish(UUID.randomUUID(), 1);
		// a (live) dispatcher claimed it and holds a lease: another dispatcher must NOT take it
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			var e = outbox.findById(event.id).orElseThrow();
			e.status = "PROCESSING";
			e.leaseUntil = Instant.now().plus(Duration.ofHours(1));
			e.attempts = 1;
		});
		drain(2);
		assertThat(reload(event.id).status).isEqualTo("PROCESSING");
		assertThat(handler.successesByEvent.get(event.id)).isNull();

		// that dispatcher died: the lease runs out and the event is re-claimed and delivered
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> outbox.findById(event.id).orElseThrow().leaseUntil = Instant.now().minusSeconds(5));
		drain(2);

		var row = reload(event.id);
		assertThat(row.status).isEqualTo("PROCESSED");
		assertThat(row.attempts).isEqualTo(2);
		assertThat(handler.successesByEvent.get(event.id)).hasValue(1);
	}

	@Test
	void eventsOfOneAggregateAreDeliveredInOrder_aFailureHoldsBackTheLaterOnes() throws Exception {
		var aggregate = UUID.randomUUID();
		handler.failures.put(aggregate, new ConcurrentLinkedDeque<>(List.of(new TransientFailure("blip", null))));
		var v1 = publish(aggregate, 1);
		var v2 = publish(aggregate, 2);
		var v3 = publish(aggregate, 3);

		dispatcher.dispatchOnce();                                                       // v1 fails -> v2 and v3 must not overtake it
		assertThat(handler.successOrderByAggregate.get(aggregate)).isNull();
		assertThat(reload(v2.id).status).isEqualTo("PENDING");
		assertThat(reload(v3.id).status).isEqualTo("PENDING");
		assertThat(reload(v2.id).attempts).isZero();                                     // not even attempted

		drain(12);

		assertThat(handler.successOrderByAggregate.get(aggregate)).containsExactly(1L, 2L, 3L);
		assertThat(List.of(reload(v1.id).status, reload(v2.id).status, reload(v3.id).status)).containsOnly("PROCESSED");
	}

	@Test
	void concurrentDispatchersNeverClaimTheSameEventTwice() throws Exception {
		var events = new ArrayList<OutboxEventEntity>();
		for (int i = 0; i < 30; i++) {
			events.add(publish(UUID.randomUUID(), 1));
		}
		var threads = new ArrayList<Thread>();
		var errors = Collections.synchronizedList(new ArrayList<Throwable>());
		for (int t = 0; t < 4; t++) {
			var thread = new Thread(() -> {
				try {
					for (int i = 0; i < 6; i++) {
						dispatcher.dispatchOnce();
						Thread.sleep(40);
					}
				} catch (Throwable e) {
					errors.add(e);
				}
			});
			threads.add(thread);
			thread.start();
		}
		for (var t : threads) {
			t.join();
		}
		drain(2);

		assertThat(errors).isEmpty();
		for (var e : events) {
			var row = reload(e.id);
			assertThat(row.status).isEqualTo("PROCESSED");
			assertThat(row.attempts).as("claimed exactly once").isEqualTo(1);            // FOR UPDATE SKIP LOCKED: no double claim
			assertThat(handler.successesByEvent.get(e.id)).hasValue(1);
		}
	}
}
