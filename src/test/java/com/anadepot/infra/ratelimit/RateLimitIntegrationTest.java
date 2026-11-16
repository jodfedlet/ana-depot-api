package com.anadepot.infra.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.config.AnaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"ana.rate-limit.limits[test.op].max-requests=3", "ana.rate-limit.limits[test.op].window=PT10M",
		"ana.rate-limit.limits[catalog.read].max-requests=2", "ana.rate-limit.limits[catalog.read].window=PT10M",
		"ana.rate-limit.trusted-proxies=10.9.9.9"})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

	@Autowired RateLimiter limiter;
	@Autowired RateLimitJpaRepository repository;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired AnaProperties properties;
	@Autowired Clock clock;
	@Autowired MeterRegistry metrics;
	@Autowired MockMvc mvc;

	private String client() {
		return "client-" + UUID.randomUUID();
	}

	@Test
	void requestsUpToTheLimitPassThenAreBlockedWithARetryAfter() {
		var who = client();
		for (int i = 0; i < 3; i++) {
			assertThat(limiter.check("test.op", who).allowed()).as("request " + (i + 1)).isTrue();
		}
		var blocked = limiter.check("test.op", who);
		assertThat(blocked.allowed()).isFalse();
		assertThat(blocked.retryAfter()).isPositive().isLessThanOrEqualTo(java.time.Duration.ofMinutes(10));
		assertThat(limiter.check("test.op", who).allowed()).isFalse();      // still blocked within the window
	}

	@Test
	void limitsAreIndependentPerClientAndPerOperation() {
		var a = client();
		var b = client();
		for (int i = 0; i < 3; i++) {
			limiter.check("test.op", a);
		}
		assertThat(limiter.check("test.op", a).allowed()).isFalse();
		assertThat(limiter.check("test.op", b).allowed()).isTrue();          // another client behind another address is unaffected
		assertThat(limiter.check("other.op", a).allowed()).isTrue();         // another operation has its own (default) limit
	}

	@Test
	void theLimitsAreSharedAcrossBackendInstancesBecauseTheyLiveInThePostgresql() {
		var instanceA = limiter;
		var instanceB = new RateLimiter(repository, transactionManager, properties, clock, metrics);   // "another server" with its own object
		var who = client();
		assertThat(instanceA.check("test.op", who).allowed()).isTrue();
		assertThat(instanceB.check("test.op", who).allowed()).isTrue();
		assertThat(instanceA.check("test.op", who).allowed()).isTrue();
		assertThat(instanceB.check("test.op", who).allowed()).isFalse();     // 4th request overall, regardless of which instance saw it
		assertThat(instanceA.check("test.op", who).allowed()).isFalse();
	}

	@Test
	void simultaneousRequestsNeverExceedTheLimit() throws Exception {
		var who = client();
		int threads = 24;
		var pool = Executors.newFixedThreadPool(threads);
		var ready = new CountDownLatch(threads);
		var go = new CountDownLatch(1);
		var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return limiter.check("test.op", who).allowed();
			}));
		}
		ready.await(10, TimeUnit.SECONDS);
		go.countDown();
		int allowed = 0;
		for (var f : futures) {
			if (f.get(60, TimeUnit.SECONDS)) {
				allowed++;
			}
		}
		pool.shutdown();
		assertThat(allowed).isEqualTo(3);
	}

	@Test
	void theHttpApiAnswers429WithRetryAfterAndAProblemBody() throws Exception {
		var ip = "203.0.113." + (1 + (int) (Math.random() * 250));
		var uuid = UUID.randomUUID();
		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/api/v1/products/" + uuid).with(r -> {
				r.setRemoteAddr(ip);
				return r;
			})).andExpect(status().isNotFound());                           // reaches the controller (the id does not exist)
		}
		mvc.perform(get("/api/v1/products/" + uuid).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.code").value("rate_limited"));
	}

	@Test
	void aForgedForwardedForHeaderFromAnUntrustedPeerDoesNotBuyAFreshBucket() throws Exception {
		var ip = "198.51.100." + (1 + (int) (Math.random() * 250));
		var uuid = UUID.randomUUID();
		int ok = 0;
		int blocked = 0;
		for (int i = 0; i < 6; i++) {
			var forged = "6.6.6." + i;
			var result = mvc.perform(get("/api/v1/products/" + uuid).header("X-Forwarded-For", forged).with(r -> {
				r.setRemoteAddr(ip);
				return r;
			})).andReturn().getResponse().getStatus();
			if (result == 429) {
				blocked++;
			} else {
				ok++;
			}
		}
		assertThat(ok).isEqualTo(2);
		assertThat(blocked).isEqualTo(4);
	}

	@Test
	void behindATrustedProxyEachRealClientHasItsOwnBucket() throws Exception {
		var uuid = UUID.randomUUID();
		var one = "192.0.2." + (1 + (int) (Math.random() * 120));
		var two = "192.0.2." + (130 + (int) (Math.random() * 120));
		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/api/v1/products/" + uuid).header("X-Forwarded-For", one).with(r -> {
				r.setRemoteAddr("10.9.9.9");
				return r;
			})).andExpect(status().isNotFound());
		}
		mvc.perform(get("/api/v1/products/" + uuid).header("X-Forwarded-For", one).with(r -> {
			r.setRemoteAddr("10.9.9.9");
			return r;
		})).andExpect(status().isTooManyRequests());
		mvc.perform(get("/api/v1/products/" + uuid).header("X-Forwarded-For", two).with(r -> {
			r.setRemoteAddr("10.9.9.9");
			return r;
		})).andExpect(status().isNotFound());                              // a different customer behind the same proxy is not punished
	}

	@Test
	void blockedRequestsAreCounted() {
		var who = client();
		double before = metrics.counter("anadepot.ratelimit", "operation", "test.op", "result", "blocked").count();
		for (int i = 0; i < 5; i++) {
			limiter.check("test.op", who);
		}
		assertThat(metrics.counter("anadepot.ratelimit", "operation", "test.op", "result", "blocked").count()).isEqualTo(before + 2);
	}
}
