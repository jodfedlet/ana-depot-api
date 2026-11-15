package com.anadepot.infra.resilience;

import java.time.Duration;

/** A failure worth retrying (timeout, 429/503, connection reset). {@code retryAfter} carries a provider's Retry-After hint. */
public class TransientFailure extends RuntimeException {

	private final Duration retryAfter;

	public TransientFailure(String message, Throwable cause) {
		this(message, cause, null);
	}

	public TransientFailure(String message, Throwable cause, Duration retryAfter) {
		super(message, cause);
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
