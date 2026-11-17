package com.anadepot.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.infra.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression: when several requests of one client open a fresh window at the same moment, the ones that lose the race to create the counter row
 * must still be counted against the limit, not refused as if the window were already full (a client far below its limit received spurious 429s).
 */
class RateLimiterFirstRequestRaceIntegrationTest extends AbstractIntegrationTest {

	@Autowired RateLimiter limiter;

	@Test
	void simultaneousFirstRequestsOfAFreshWindowAreAllAllowedFarBelowTheLimit() throws Exception {
		int threads = 16;
		var pool = Executors.newFixedThreadPool(threads);
		int refused = 0;
		for (int round = 0; round < 25; round++) {
			var client = "user:" + UUID.randomUUID();           // a fresh bucket each round: every round races to create the window row
			var start = new CountDownLatch(1);
			var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return limiter.check("admin.write", client).allowed();      // limit is 100000 per minute in the test profile
				}));
			}
			start.countDown();
			for (var f : results) {
				if (!f.get()) {
					refused++;
				}
			}
		}
		pool.shutdown();
		assertThat(refused).as("requests refused far below the limit").isZero();
	}
}
