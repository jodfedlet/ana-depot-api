package com.anadepot.infra.ratelimit;

import com.anadepot.infra.config.AnaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixed-window rate limiter whose counters live in PostgreSQL, so every backend instance shares them (docs/adr/0006).
 * One conditional {@code UPDATE ... WHERE count < max} per request decides atomically; the first request of a window
 * inserts the row (a race on the insert falls back to the update). Each check commits independently of the request's own
 * transaction. Trade-off: a database write per limited request and a window that resets abruptly (bursts at the
 * boundary); acceptable at Ana Dépôt's scale - swap the store for Redis if traffic ever demands it.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final RateLimitJpaRepository repository;
	private final TransactionTemplate tx;
	private final AnaProperties.RateLimit config;
	private final Clock clock;
	private final MeterRegistry metrics;

	RateLimiter(RateLimitJpaRepository repository, PlatformTransactionManager manager, AnaProperties properties, Clock clock, MeterRegistry metrics) {
		this.repository = repository;
		this.tx = new TransactionTemplate(manager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.config = properties.rateLimit();
		this.clock = clock;
		this.metrics = metrics;
	}

	public AnaProperties.Limit limitFor(String operation) {
		var limits = config.limits();
		return limits != null && limits.containsKey(operation) ? limits.get(operation) : config.defaultLimit();
	}

	/** Counts one request of {@code clientKey} for {@code operation} against its configured limit. */
	public RateLimitDecision check(String operation, String clientKey) {
		if (!config.enabled()) {
			return new RateLimitDecision(true, Duration.ZERO);
		}
		var limit = limitFor(operation);
		var now = clock.instant();
		long windowMs = limit.window().toMillis();
		var windowStart = Instant.ofEpochMilli(now.toEpochMilli() / windowMs * windowMs);
		var bucket = operation + "|" + clientKey;
		boolean allowed;
		try {
			allowed = Boolean.TRUE.equals(tx.execute(s -> tryAcquire(bucket, windowStart, limit.maxRequests())));
		} catch (DataIntegrityViolationException raced) {
			// Another request created the window's row first. PostgreSQL aborted that transaction, so retry in a NEW one.
			allowed = Boolean.TRUE.equals(tx.execute(s -> repository.increment(bucket, windowStart, limit.maxRequests()) == 1));
		}
		if (allowed) {
			return new RateLimitDecision(true, Duration.ZERO);
		}
		metrics.counter("anadepot.ratelimit", "operation", operation, "result", "blocked").increment();
		log.warn("rate limit exceeded operation={} client={}", operation, Integer.toHexString(clientKey.hashCode())); // hashed: no raw IPs in logs
		var retryAfter = Duration.between(now, windowStart.plusMillis(windowMs));
		return new RateLimitDecision(false, retryAfter.isNegative() ? Duration.ofSeconds(1) : retryAfter);
	}

	private boolean tryAcquire(String bucket, Instant windowStart, int max) {
		if (repository.increment(bucket, windowStart, max) == 1) {
			return true;
		}
		if (repository.existsByBucketAndWindowStart(bucket, windowStart)) {
			return false; // row exists and is full
		}
		var row = new RateLimitCounterEntity();
		row.bucket = bucket;
		row.windowStart = windowStart;
		row.requestCount = 1;
		repository.saveAndFlush(row);   // a concurrent creator makes this fail: handled by the caller, in a fresh transaction
		return true;
	}

	@Scheduled(fixedDelayString = "PT15M")
	void cleanup() {
		var deleted = tx.execute(s -> repository.deleteOlderThan(clock.instant().minus(config.cleanupRetention())));
		if (deleted != null && deleted > 0) {
			log.debug("Removed {} stale rate-limit windows", deleted);
		}
	}
}
