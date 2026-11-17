package com.anadepot.infra.storage;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.DomainRuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Object-storage reachability for the {@code dependencies} health group (NOT for readiness: the catalog, reservations and payments work without
 * the store, so an outage must not take every instance out of rotation; uploads fail with 503 {@code storage_unavailable} instead).
 *
 * The probe is a metadata lookup of a key that does not exist: "not found" proves the store answered and the credentials work; an unavailable
 * store raises {@code storage_unavailable}. The health endpoint is public, so the result is CACHED for a few seconds: callers can not turn it
 * into a request amplifier against the bucket. A store that is not configured is {@code UNKNOWN}, not {@code DOWN} (the feature is off, not broken).
 */
@Component("storage")
class StorageHealthIndicator implements HealthIndicator {

	static final Duration CACHE = Duration.ofSeconds(30);
	private static final String PROBE_KEY = "_health/probe";

	private final ObjectStorage storage;
	private final Clock clock;
	private Health cached;
	private Instant checkedAt = Instant.MIN;

	StorageHealthIndicator(ObjectStorage storage, Clock clock) {
		this.storage = storage;
		this.clock = clock;
	}

	@Override
	public synchronized Health health() {
		var now = clock.instant();
		if (cached == null || Duration.between(checkedAt, now).compareTo(CACHE) >= 0) {
			cached = probe();
			checkedAt = now;
		}
		return cached;
	}

	private Health probe() {
		try {
			storage.inspect(PROBE_KEY, 1);
			return Health.up().build();
		} catch (DomainRuleViolation e) {
			// The status only: never the exception message (it could name the endpoint) and never credentials.
			return "storage_not_configured".equals(e.code()) ? Health.unknown().build() : Health.down().build();
		} catch (RuntimeException e) {
			return Health.down().build();
		}
	}
}
