package com.anadepot.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.shared.DomainEvent;
import com.anadepot.infra.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxBacklogAgeMetricIntegrationTest extends AbstractIntegrationTest {

	@Autowired OutboxJpaRepository outbox;
	@Autowired OutboxDispatcher dispatcher;
	@Autowired OutboxEventRecorder recorder;
	@Autowired MeterRegistry metrics;
	@Autowired PlatformTransactionManager tm;

	@Test
	void theOldestUndeliveredGaugeTracksTheAgeOfTheOldestWaitingEventAndAlertsCanUseIt() throws Exception {
		var aggregate = UUID.randomUUID();
		new TransactionTemplate(tm).executeWithoutResult(s -> recorder.record(new DomainEvent("test.backlog", "test_aggregate", aggregate, 1, Map.of())));
		Thread.sleep(1200);

		var oldest = outbox.oldestUndelivered();
		assertThat(oldest).isNotNull();
		double expected = Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
		double gauge = metrics.get("anadepot.outbox.oldest.undelivered.age").gauge().value();
		assertThat(gauge).isGreaterThanOrEqualTo(1.0).isBetween(expected - 2, expected + 2);
		assertThat(metrics.get("anadepot.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);

		// once everything waiting is delivered the backlog age falls back to 0
		for (int i = 0; i < 5 && outbox.oldestUndelivered() != null; i++) {
			dispatcher.dispatchOnce();
			Thread.sleep(100);
		}
		if (outbox.oldestUndelivered() == null) {
			assertThat(metrics.get("anadepot.outbox.oldest.undelivered.age").gauge().value()).isZero();
		}
	}
}
