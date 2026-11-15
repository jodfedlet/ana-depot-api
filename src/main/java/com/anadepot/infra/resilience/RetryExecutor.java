package com.anadepot.infra.resilience;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.DoubleSupplier;

/**
 * Runs a call and retries ONLY {@link TransientFailure}s under a {@link RetryPolicy}. Anything else (including
 * {@link PermanentFailure}) is thrown immediately. Callers must make the call idempotent before retrying a write
 * (see IdempotencyService): a retry must never duplicate a payment, reservation, upload or notification.
 */
public final class RetryExecutor {

	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private final RetryPolicy policy;
	private final Sleeper sleeper;
	private final DoubleSupplier random;

	public RetryExecutor(RetryPolicy policy, Sleeper sleeper, DoubleSupplier random) {
		this.policy = policy;
		this.sleeper = sleeper;
		this.random = random;
	}

	public <T> T call(Callable<T> call) throws Exception {
		var started = System.nanoTime();
		for (int attempt = 1; ; attempt++) {
			try {
				return call.call();
			} catch (TransientFailure failure) {
				var elapsed = Duration.ofNanos(System.nanoTime() - started);
				if (!policy.attemptsLeft(attempt)) {
					throw failure;
				}
				var delay = policy.delayAfter(attempt, failure.retryAfter(), random);
				if (elapsed.plus(delay).compareTo(policy.maxElapsed()) > 0) {
					throw failure;
				}
				sleeper.sleep(delay);
			}
		}
	}
}
