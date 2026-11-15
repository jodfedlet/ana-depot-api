package com.anadepot.infra.persistence;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.IdempotencyRequest;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.IdempotentOutcome;
import com.anadepot.infra.config.AnaProperties;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency storage (docs/adr/0004). The key row is inserted in the SAME transaction as the business change, and a
 * unique constraint on (actor scope, operation, key) arbitrates races:
 * <ol>
 *   <li>look the key up; found -> same fingerprint: return the stored resource; different: reject;</li>
 *   <li>not found -> in ONE transaction insert the key, run the work and store the resource id. If a concurrent request
 *       inserted the same key first, our insert fails on the unique constraint (after waiting for the other
 *       transaction to finish), our whole transaction rolls back - including the work - and we go back to step 1,
 *       which now finds the winner's committed result.</li>
 * </ol>
 * Works across restarts and instances because all state is in PostgreSQL.
 */
@Component
public class JpaIdempotencyService implements IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(JpaIdempotencyService.class);
	private static final int MAX_ATTEMPTS = 5;

	private final IdempotencyKeyJpaRepository repository;
	private final TransactionTemplate readTx;
	private final TransactionTemplate writeTx;
	private final AnaProperties properties;
	private final Clock clock;
	private final io.micrometer.core.instrument.MeterRegistry metrics;

	public JpaIdempotencyService(IdempotencyKeyJpaRepository repository, PlatformTransactionManager manager, AnaProperties properties, Clock clock,
			io.micrometer.core.instrument.MeterRegistry metrics) {
		this.repository = repository;
		this.readTx = new TransactionTemplate(manager);
		this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTx.setReadOnly(true);
		this.writeTx = new TransactionTemplate(manager);
		this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Override
	public IdempotentOutcome execute(IdempotencyRequest request, Supplier<UUID> work) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			var existing = readTx.execute(s -> repository.findByActorScopeAndOperationAndIdempotencyKey(request.actorScope(), request.operation(), request.key()).orElse(null));
			if (existing != null) {
				return replay(request, existing);
			}
			try {
				return writeTx.execute(status -> {
					var key = new IdempotencyKeyEntity();
					key.id = UUID.randomUUID();
					key.actorScope = request.actorScope();
					key.operation = request.operation();
					key.idempotencyKey = request.key();
					key.requestFingerprint = request.fingerprint();
					key.createdAt = clock.instant();
					key.expiresAt = key.createdAt.plus(properties.idempotency().retention());
					key.resourceId = new UUID(0, 0); // placeholder until the work finishes; the row is only visible after commit
					repository.saveAndFlush(key); // blocks here if a concurrent identical request is in flight, then fails on commit
					key.resourceId = work.get();
					repository.saveAndFlush(key);
					return new IdempotentOutcome(key.resourceId, false);
				});
			} catch (DataIntegrityViolationException race) {
				metrics.counter("anadepot.idempotency", "result", "race").increment();
				log.debug("Concurrent request for the same idempotency key; re-reading its result (attempt {})", attempt);
			}
		}
		throw new DomainRuleViolation("idempotency_conflict", "Could not settle the idempotent request; retry later");
	}

	private IdempotentOutcome replay(IdempotencyRequest request, IdempotencyKeyEntity existing) {
		if (!existing.requestFingerprint.equals(request.fingerprint())) {
			metrics.counter("anadepot.idempotency", "result", "key_reuse").increment();
			throw new DomainRuleViolation("idempotency_key_reuse", "This idempotency key was already used with a different request");
		}
		metrics.counter("anadepot.idempotency", "result", "replayed").increment();
		return new IdempotentOutcome(existing.resourceId, true);
	}

	/** Safe to run on every instance, any number of times. */
	@Scheduled(fixedDelayString = "${ana.idempotency.cleanup-interval:PT1H}")
	public void cleanupExpired() {
		var deleted = writeTx.execute(s -> repository.deleteExpired(clock.instant()));
		if (deleted != null && deleted > 0) {
			log.info("Removed {} expired idempotency keys", deleted);
		}
	}
}
