package com.anadepot.infra.ratelimit;

import java.time.Duration;

/** A limit that is checked inside a controller (keyed by something other than the caller, such as a reservation reference). Rendered as 429 + Retry-After. */
public class RateLimitExceededException extends RuntimeException {

	private final Duration retryAfter;

	public RateLimitExceededException(Duration retryAfter) {
		super("Too many requests");
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
