package com.anadepot.core.shared;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Server-side idempotency. The key, the business change and the stored result are committed atomically; a unique
 * database constraint on (actor scope, operation, key) makes this safe across restarts and multiple instances.
 */
public interface IdempotencyService {

	/**
	 * Runs {@code work} at most once for the request. A repeat of an identical request returns the original resource
	 * without running {@code work}; the same key with a different payload fails with {@code idempotency_key_reuse}.
	 */
	IdempotentOutcome execute(IdempotencyRequest request, Supplier<UUID> work);

	/** Convenience for use cases: no key = plain execution (idempotency is opt-in per request). */
	default <T> T run(String key, String actorScope, String operation, String fingerprint, Supplier<UUID> work, Function<UUID, T> load) {
		if (key == null || key.isBlank()) {
			return load.apply(work.get());
		}
		return load.apply(execute(new IdempotencyRequest(actorScope, operation, key.strip(), fingerprint), work).resourceId());
	}
}
