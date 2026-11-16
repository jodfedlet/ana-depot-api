package com.anadepot.infra.ratelimit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "rate_limit_counters")
@IdClass(RateLimitCounterEntity.Key.class)
class RateLimitCounterEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<RateLimitCounterEntity.Key> {

	@Id
	String bucket;
	@Id
	Instant windowStart;
	int requestCount;

	protected RateLimitCounterEntity() {
	}

	@Override
	public Key getId() {
		return new Key(bucket, windowStart);
	}

	record Key(String bucket, Instant windowStart) implements Serializable {
	}
}
