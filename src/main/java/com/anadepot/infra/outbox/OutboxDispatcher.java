package com.anadepot.infra.outbox;

import com.anadepot.infra.config.AnaProperties;
import com.anadepot.infra.persistence.Json;
import com.anadepot.infra.resilience.PermanentFailure;
import com.anadepot.infra.resilience.RetryPolicy;
import com.anadepot.infra.resilience.TransientFailure;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed outbox dispatcher (docs/adr/0002). Three SHORT transactions per batch, and no transaction is open while a
 * handler runs:
 * <ol>
 *   <li><b>claim</b>: lock due events ({@code FOR UPDATE SKIP LOCKED}), mark PROCESSING with a lease, commit;</li>
 *   <li><b>deliver</b>: call handlers (deduplicated per consumer + event id);</li>
 *   <li><b>settle</b>: PROCESSED, or back to PENDING with exponential backoff + jitter, or DEAD after {@code maxAttempts}
 *       / a permanent failure.</li>
 * </ol>
 * A dispatcher that crashes leaves an expired lease and the event is claimed again. Delivery is at-least-once, never
 * exactly-once. Safe to run on several instances and to call repeatedly.
 */
@Component
public class OutboxDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

	private final OutboxJpaRepository repository;
	private final List<OutboxEventHandler> handlers;
	private final EventDeduplicator deduplicator;
	private final AnaProperties.Outbox config;
	private final RetryPolicy retryPolicy;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final MeterRegistry metrics;

	OutboxDispatcher(OutboxJpaRepository repository, List<OutboxEventHandler> handlers, EventDeduplicator deduplicator, AnaProperties properties,
			PlatformTransactionManager manager, Clock clock, MeterRegistry metrics) {
		this.repository = repository;
		this.handlers = handlers;
		this.deduplicator = deduplicator;
		this.config = properties.outbox();
		this.retryPolicy = new RetryPolicy(config.maxAttempts(), config.initialBackoff(), config.backoffMultiplier(), config.maxBackoff(), java.time.Duration.ofDays(3650));
		this.tx = new TransactionTemplate(manager);
		this.clock = clock;
		this.metrics = metrics;
		Gauge.builder("anadepot.outbox.pending", repository, r -> r.countByStatus("PENDING")).register(metrics);
		Gauge.builder("anadepot.outbox.dead", repository, r -> r.countByStatus("DEAD")).register(metrics);
		// Age of the oldest event not yet delivered: a growing value means the dispatcher (or a handler) is stuck even when no event is DEAD yet.
		Gauge.builder("anadepot.outbox.oldest.undelivered.age", repository, r -> {
			var oldest = r.oldestUndelivered();
			return oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
		}).baseUnit("seconds").description("Age in seconds of the oldest outbox event that is not delivered yet (0 when none)").register(metrics);
	}

	@Scheduled(fixedDelayString = "${ana.outbox.poll-interval:PT2S}")
	void poll() {
		if (config.dispatcherEnabled()) {
			try {
				dispatchOnce();
			} catch (RuntimeException e) {
				log.error("Outbox dispatch cycle failed; will retry on the next poll", e);
			}
		}
	}

	@Scheduled(cron = "${ana.outbox.cleanup-cron:0 17 3 * * *}")
	void cleanup() {
		var deleted = tx.execute(s -> repository.deleteProcessedBefore(clock.instant().minus(config.processedRetention())));
		if (deleted != null && deleted > 0) {
			log.info("Removed {} processed outbox events", deleted);
		}
	}

	/** One dispatch cycle. @return number of events delivered successfully. */
	public int dispatchOnce() {
		var claimed = claim();
		int delivered = 0;
		var blocked = new HashSet<java.util.UUID>(); // after a failure, later events of the same aggregate wait (ordering)
		for (var m : claimed) {
			if (blocked.contains(m.aggregateId())) {
				release(m);
				continue;
			}
			try {
				deliver(m);
				settleSuccess(m);
				delivered++;
			} catch (RuntimeException failure) {
				blocked.add(m.aggregateId());
				settleFailure(m, failure);
			}
		}
		return delivered;
	}

	private List<OutboxMessage> claim() {
		return tx.execute(status -> {
			var now = clock.instant();
			var rows = repository.claimDue(now, PageRequest.of(0, config.batchSize()));
			var messages = new ArrayList<OutboxMessage>();
			for (var e : rows) {
				e.status = "PROCESSING";
				e.leaseUntil = now.plus(config.leaseDuration());
				e.attempts++;
				messages.add(new OutboxMessage(e.id, e.eventType, e.aggregateType, e.aggregateId, e.aggregateVersion, Json.readMap(e.payload), e.correlationId, e.attempts));
			}
			return messages;
		});
	}

	private void deliver(OutboxMessage m) {
		for (var handler : handlers) {
			if (handler.supports(m.type())) {
				deduplicator.runOnce(handler.name(), m.eventId().toString(), () -> handler.handle(m));
			}
		}
	}

	private void settleSuccess(OutboxMessage m) {
		tx.executeWithoutResult(s -> repository.findById(m.eventId()).ifPresent(e -> {
			e.status = "PROCESSED";
			e.processedAt = clock.instant();
			e.leaseUntil = null;
			e.lastError = null;
		}));
		metrics.counter("anadepot.outbox.events", "result", "delivered").increment();
	}

	private void settleFailure(OutboxMessage m, RuntimeException failure) {
		boolean permanent = failure instanceof PermanentFailure;
		var retryAfter = failure instanceof TransientFailure t ? t.retryAfter() : null;
		tx.executeWithoutResult(s -> repository.findById(m.eventId()).ifPresent(e -> {
			e.lastError = truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage());
			e.leaseUntil = null;
			if (permanent || e.attempts >= config.maxAttempts()) {
				e.status = "DEAD";
				log.error("Outbox event {} ({}) moved to dead letter after {} attempts: {}", e.id, e.eventType, e.attempts, e.lastError);
			} else {
				e.status = "PENDING";
				e.nextAttemptAt = Instant.now(clock).plus(retryPolicy.delayAfter(e.attempts, retryAfter, () -> ThreadLocalRandom.current().nextDouble()));
			}
		}));
		metrics.counter("anadepot.outbox.events", "result", permanent ? "dead" : "failed").increment();
	}

	/** Gives an unattempted event back (it was held behind a failed predecessor). */
	private void release(OutboxMessage m) {
		tx.executeWithoutResult(s -> repository.findById(m.eventId()).ifPresent(e -> {
			e.status = "PENDING";
			e.leaseUntil = null;
			e.attempts = Math.max(0, e.attempts - 1);
		}));
	}

	private static String truncate(String s) {
		return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
	}
}
