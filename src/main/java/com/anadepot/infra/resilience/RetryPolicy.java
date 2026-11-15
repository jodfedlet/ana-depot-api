package com.anadepot.infra.resilience;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with "equal jitter": attempt n waits a random time in [cap/2, cap] where
 * {@code cap = min(maxDelay, initialDelay * multiplier^(n-1))}. Jitter keeps many clients from retrying in lock step.
 * Bounded twice: {@code maxAttempts} and {@code maxElapsed}. A provider's Retry-After is honoured as a lower bound
 * (but never beyond {@code maxDelay}).
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay, Duration maxElapsed) {

	public RetryPolicy {
		if (maxAttempts < 1 || multiplier < 1.0 || initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
			throw new IllegalArgumentException("Invalid retry policy");
		}
	}

	/** Delay to wait after failed attempt number {@code failedAttempt} (1-based). */
	public Duration delayAfter(int failedAttempt, Duration retryAfterHint, DoubleSupplier random01) {
		double exponential = initialDelay.toMillis() * Math.pow(multiplier, failedAttempt - 1);
		long cap = (long) Math.min(maxDelay.toMillis(), exponential);
		long jittered = cap / 2 + (long) (random01.getAsDouble() * (cap - cap / 2));
		if (retryAfterHint != null) {
			jittered = Math.max(jittered, Math.min(retryAfterHint.toMillis(), maxDelay.toMillis()));
		}
		return Duration.ofMillis(jittered);
	}

	public boolean attemptsLeft(int failedAttempt) {
		return failedAttempt < maxAttempts;
	}
}
