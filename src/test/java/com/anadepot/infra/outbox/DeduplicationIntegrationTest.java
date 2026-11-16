package com.anadepot.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.infra.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Duplicate webhooks / replayed messages: the business effect happens once, the duplicates are counted. */
class DeduplicationIntegrationTest extends AbstractIntegrationTest {

	@Autowired EventDeduplicator deduplicator;

	private String id() {
		return "evt_" + UUID.randomUUID();
	}

	@Test
	void aDuplicateWebhookDeliveryRunsTheEffectOnce() {
		var effect = new AtomicInteger();
		var event = id();
		assertThat(deduplicator.runOnce("provider-x", event, effect::incrementAndGet)).isTrue();
		assertThat(deduplicator.runOnce("provider-x", event, effect::incrementAndGet)).isFalse();
		assertThat(deduplicator.runOnce("provider-x", event, effect::incrementAndGet)).isFalse();
		assertThat(effect).hasValue(1);
		assertThat(deduplicator.duplicateCount("provider-x", event)).isEqualTo(2);       // duplicates stay observable
	}

	@Test
	void sameEventIdForADifferentConsumerIsIndependent() {
		var effect = new AtomicInteger();
		var event = id();
		deduplicator.runOnce("consumer-a", event, effect::incrementAndGet);
		deduplicator.runOnce("consumer-b", event, effect::incrementAndGet);
		assertThat(effect).hasValue(2);
	}

	@Test
	void simultaneousDuplicatesProduceExactlyOneEffect() throws Exception {
		var effect = new AtomicInteger();
		var event = id();
		int threads = 8;
		var pool = Executors.newFixedThreadPool(threads);
		var ready = new CountDownLatch(threads);
		var go = new CountDownLatch(1);
		var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return deduplicator.runOnce("provider-y", event, effect::incrementAndGet);
			}));
		}
		ready.await(10, TimeUnit.SECONDS);
		go.countDown();
		int firsts = 0;
		for (var f : futures) {
			if (f.get(60, TimeUnit.SECONDS)) {
				firsts++;
			}
		}
		pool.shutdown();
		assertThat(firsts).isEqualTo(1);
		assertThat(effect).hasValue(1);
		assertThat(deduplicator.duplicateCount("provider-y", event)).isEqualTo(threads - 1);
	}

	@Test
	void aFailedEffectDoesNotMarkTheEventProcessed_soItCanBeRetried() {
		var event = id();
		var effect = new AtomicInteger();
		assertThatThrownBy(() -> deduplicator.runOnce("provider-z", event, () -> {
			throw new IllegalStateException("downstream error");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(deduplicator.runOnce("provider-z", event, effect::incrementAndGet)).isTrue();   // first SUCCESSFUL processing
		assertThat(effect).hasValue(1);
		assertThat(deduplicator.duplicateCount("provider-z", event)).isZero();
	}
}
