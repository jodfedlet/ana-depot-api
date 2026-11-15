package com.anadepot.infra.outbox;

/**
 * A consumer of outbox events. Delivery is AT LEAST ONCE, so handlers must be idempotent; the dispatcher already
 * deduplicates per {@link #name()} + event id. Throw {@code TransientFailure} to be retried with backoff, or
 * {@code PermanentFailure} (or any other exception counts as transient) to dead-letter immediately.
 */
public interface OutboxEventHandler {

	/** Stable consumer name (used as the deduplication scope). */
	String name();

	boolean supports(String eventType);

	void handle(OutboxMessage message);
}
