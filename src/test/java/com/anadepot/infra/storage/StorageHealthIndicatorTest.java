package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.DomainRuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class StorageHealthIndicatorTest {

	/** A clock the test moves. */
	static class MovableClock extends Clock {
		Instant now = Instant.parse("2026-11-17T10:00:00Z");
		public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
		public Clock withZone(java.time.ZoneId z) { return this; }
		public Instant instant() { return now; }
	}

	static class Store implements ObjectStorage {
		final AtomicInteger probes = new AtomicInteger();
		volatile RuntimeException failure;
		public PresignedUpload presignPut(String k, String c, long m, Duration t) { throw new UnsupportedOperationException(); }
		public Optional<StoredObject> inspect(String key, int head) { probes.incrementAndGet(); if (failure != null) throw failure; return Optional.empty(); }
		public void delete(String k) { throw new UnsupportedOperationException(); }
		public String publicUrl(String k) { return k; }
	}

	@Test
	void aStoreThatAnswersNotFoundIsUp_anUnavailableOneIsDown_anUnconfiguredOneIsUnknown() {
		var store = new Store();
		var clock = new MovableClock();
		var indicator = new StorageHealthIndicator(store, clock);
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

		clock.now = clock.now.plus(StorageHealthIndicator.CACHE);
		store.failure = new DomainRuleViolation("storage_unavailable", "Object storage is temporarily unavailable at https://secret-host.example");
		var down = indicator.health();
		assertThat(down.getStatus()).isEqualTo(Status.DOWN);
		assertThat(down.getDetails()).as("no endpoint, message or credentials in the health body").isEmpty();

		clock.now = clock.now.plus(StorageHealthIndicator.CACHE);
		store.failure = new DomainRuleViolation("storage_not_configured", "Object storage is not configured");
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

		clock.now = clock.now.plus(StorageHealthIndicator.CACHE);
		store.failure = new IllegalStateException("socket closed");
		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	void theProbeIsCachedSoThePublicHealthEndpointCannotBeUsedToHammerTheBucket() {
		var store = new Store();
		var clock = new MovableClock();
		var indicator = new StorageHealthIndicator(store, clock);
		for (int i = 0; i < 200; i++) indicator.health();
		assertThat(store.probes).hasValue(1);
		clock.now = clock.now.plus(StorageHealthIndicator.CACHE).plusSeconds(1);
		indicator.health();
		assertThat(store.probes).hasValue(2);
	}
}
