package com.anadepot.infra.ratelimit;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RateLimitJpaRepository extends JpaRepository<RateLimitCounterEntity, RateLimitCounterEntity.Key> {

	/** Atomic "count one more request if the window still has room": 1 = allowed, 0 = limit reached (or no row yet). */
	@Modifying
	@Query("update RateLimitCounterEntity c set c.requestCount = c.requestCount + 1 where c.bucket = :bucket and c.windowStart = :windowStart and c.requestCount < :max")
	int increment(String bucket, Instant windowStart, int max);

	boolean existsByBucketAndWindowStart(String bucket, Instant windowStart);

	@Modifying
	@Query("delete from RateLimitCounterEntity c where c.windowStart < :before")
	int deleteOlderThan(Instant before);
}
