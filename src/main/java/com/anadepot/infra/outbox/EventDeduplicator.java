package com.anadepot.infra.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deduplication (docs/adr/0005). {@code runOnce} records "consumer X processed event Y" and runs the effect in ONE
 * transaction guarded by a unique (consumer, event id) constraint: the second delivery of the same event - from a
 * redelivered outbox row, a duplicated provider webhook or a replay - is detected, counted and skipped. If the effect
 * fails, the mark rolls back with it, so the event can be retried. Effects outside the database (HTTP calls) are still
 * at-least-once and must be idempotent themselves.
 */
@Component
public class EventDeduplicator {

	private final ProcessedEventJpaRepository repository;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final MeterRegistry metrics;

	EventDeduplicator(ProcessedEventJpaRepository repository, PlatformTransactionManager manager, Clock clock, MeterRegistry metrics) {
		this.repository = repository;
		this.tx = new TransactionTemplate(manager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.metrics = metrics;
	}

	/** @return true when this was the first delivery and {@code effect} ran; false when it was a duplicate (effect skipped). */
	public boolean runOnce(String consumer, String eventId, Runnable effect) {
		try {
			boolean first = Boolean.TRUE.equals(tx.execute(status -> {
				if (repository.findByConsumerAndEventId(consumer, eventId).isPresent()) {
					return false;
				}
				var row = new ProcessedEventEntity();
				row.id = UUID.randomUUID();
				row.consumer = consumer;
				row.eventId = eventId;
				row.firstProcessedAt = clock.instant();
				repository.saveAndFlush(row);   // the unique constraint is the arbiter of concurrent duplicates
				effect.run();
				return true;
			}));
			if (!first) {
				recordDuplicate(consumer, eventId);
			}
			return first;
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			recordDuplicate(consumer, eventId);
			return false;
		}
	}

	private void recordDuplicate(String consumer, String eventId) {
		tx.executeWithoutResult(s -> repository.markDuplicate(consumer, eventId, clock.instant()));
		metrics.counter("anadepot.dedup", "consumer", consumer, "result", "duplicate").increment();
	}

	public int duplicateCount(String consumer, String eventId) {
		return tx.execute(s -> repository.findByConsumerAndEventId(consumer, eventId).map(p -> p.duplicateCount).orElse(0));
	}
}
