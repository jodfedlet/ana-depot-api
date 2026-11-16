package com.anadepot.infra.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.infra.resilience.PermanentFailure;
import com.anadepot.infra.resilience.RetryExecutor;
import com.anadepot.infra.resilience.RetryPolicy;
import com.anadepot.infra.resilience.TransientFailure;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	private final RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), Duration.ofSeconds(30));

	@Test
	void delaysGrowExponentiallyAndAreCappedAtMaxDelay() {
		// random01 = 1.0 -> the top of the jitter range = the cap itself
		assertThat(policy.delayAfter(1, null, () -> 1.0)).isEqualTo(Duration.ofMillis(100));
		assertThat(policy.delayAfter(2, null, () -> 1.0)).isEqualTo(Duration.ofMillis(200));
		assertThat(policy.delayAfter(3, null, () -> 1.0)).isEqualTo(Duration.ofMillis(400));
		assertThat(policy.delayAfter(4, null, () -> 1.0)).isEqualTo(Duration.ofMillis(800));
		assertThat(policy.delayAfter(5, null, () -> 1.0)).isEqualTo(Duration.ofSeconds(1)); // 1600 capped to 1000
		assertThat(policy.delayAfter(30, null, () -> 1.0)).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void jitterSpreadsDelaysBetweenHalfAndFullOfTheCap_soClientsDoNotRetryInLockStep() {
		assertThat(policy.delayAfter(3, null, () -> 0.0)).isEqualTo(Duration.ofMillis(200)); // bottom: cap/2
		assertThat(policy.delayAfter(3, null, () -> 0.5)).isEqualTo(Duration.ofMillis(300));
		var seen = new java.util.HashSet<Long>();
		var random = new java.util.Random(7);
		for (int i = 0; i < 200; i++) {
			var d = policy.delayAfter(4, null, random::nextDouble).toMillis();
			assertThat(d).isBetween(400L, 800L);
			seen.add(d);
		}
		assertThat(seen.size()).isGreaterThan(50); // genuinely spread, not a constant
	}

	@Test
	void aProvidersRetryAfterIsAFloor_butNeverBeyondMaxDelay() {
		assertThat(policy.delayAfter(1, Duration.ofMillis(700), () -> 0.0)).isEqualTo(Duration.ofMillis(700));
		assertThat(policy.delayAfter(1, Duration.ofMinutes(10), () -> 0.0)).isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void retriesOnlyTransientFailuresAndStopsOnSuccess() throws Exception {
		var sleeps = new ArrayList<Duration>();
		var executor = new RetryExecutor(policy, sleeps::add, () -> 0.0);
		var calls = new AtomicInteger();
		var result = executor.call(() -> {
			if (calls.incrementAndGet() < 3) {
				throw new TransientFailure("503", null);
			}
			return "ok";
		});
		assertThat(result).isEqualTo("ok");
		assertThat(calls).hasValue(3);
		assertThat(sleeps).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100)); // exponential: 50 -> 100 (jitter floor)
	}

	@Test
	void permanentFailuresAreNeverRetried() {
		var sleeps = new ArrayList<Duration>();
		var executor = new RetryExecutor(policy, sleeps::add, () -> 0.0);
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> executor.call(() -> {
			calls.incrementAndGet();
			throw new PermanentFailure("400 invalid");
		})).isInstanceOf(PermanentFailure.class);
		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
		assertThatThrownBy(() -> executor.call(() -> {
			calls.incrementAndGet();
			throw new IllegalArgumentException("a bug is not a transient error");
		})).isInstanceOf(IllegalArgumentException.class);
		assertThat(calls).hasValue(2);
	}

	@Test
	void retriesAreBoundedByMaxAttempts() {
		var sleeps = new ArrayList<Duration>();
		var executor = new RetryExecutor(policy, sleeps::add, () -> 1.0);
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> executor.call(() -> {
			calls.incrementAndGet();
			throw new TransientFailure("timeout", null);
		})).isInstanceOf(TransientFailure.class);
		assertThat(calls).hasValue(5);
		assertThat(sleeps).hasSize(4);
	}

	@Test
	void retriesAreBoundedByMaxElapsedTime() {
		var tight = new RetryPolicy(10, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), Duration.ofSeconds(2));
		var slept = new ArrayList<Duration>();
		var executor = new RetryExecutor(tight, slept::add, () -> 1.0);
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> executor.call(() -> {
			calls.incrementAndGet();
			throw new TransientFailure("down", null);
		})).isInstanceOf(TransientFailure.class);
		assertThat(calls.get()).isLessThan(10); // stopped by elapsed budget (1s + 2s would exceed 2s), not by attempts
		List<Duration> total = slept;
		assertThat(total.stream().mapToLong(Duration::toMillis).sum()).isLessThanOrEqualTo(2000);
	}

	@Test
	void aPolicyMustBeSane() {
		assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(1), 2.0, Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(1), 0.5, Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
	}
}
