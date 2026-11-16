package com.anadepot.infra.reservation;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@TestPropertySource(properties = {
		"ana.rate-limit.limits[reservation.submit].max-requests=3", "ana.rate-limit.limits[reservation.submit].window=PT10M",
		"ana.rate-limit.limits[reservation.lookup].max-requests=4", "ana.rate-limit.limits[reservation.lookup].window=PT10M",
		"ana.rate-limit.limits[reservation.lookup.reference].max-requests=3", "ana.rate-limit.limits[reservation.lookup.reference].window=PT10M"})
class ReservationRateLimitIntegrationTest extends ReservationHttpSupport {

	@BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	private static String ip() {
		return "198.51.100." + (1 + (int) (Math.random() * 250));
	}

	@Test
	void submissionsArePerClientLimited() throws Exception {
		var mattress = stockedMattress(20);
		var ip = ip();
		for (int i = 0; i < 3; i++) {
			mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations").with(r -> {
				r.setRemoteAddr(ip);
				return r;
			}).contentType("application/json").header("Idempotency-Key", key()).content(json(request(freshPhone(), line(mattress, 1, "HTG"))))).andExpect(status().isCreated());
		}
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType("application/json").header("Idempotency-Key", key()).content(json(request(freshPhone(), line(mattress, 1, "HTG")))))
				.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("rate_limited"));
		// another client is not affected
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations").with(r -> {
			r.setRemoteAddr(ip());
			return r;
		}).contentType("application/json").header("Idempotency-Key", key()).content(json(request(freshPhone(), line(mattress, 1, "HTG"))))).andExpect(status().isCreated());
	}

	@Test
	void lookupsArePerClientLimited() throws Exception {
		var ip = ip();
		for (int i = 0; i < 4; i++) {
			lookupFrom(ip, "ANA-ABCDE" + (i + 2), "30000000").andExpect(status().isNotFound());
		}
		lookupFrom(ip, "ANA-ABCDE2", "30000000").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
	}

	@Test
	void guessingOneReferencesPhoneFromManyAddressesIsStoppedByTheReferenceThrottle() throws Exception {
		var mattress = stockedMattress(5);
		var phone = freshPhone();
		var ref = submit(key(), line(mattress, 1, com.anadepot.core.shared.money.SupportedCurrency.HTG), phone).reference();   // through the use case: the HTTP submit limit is 3 here
		for (int i = 0; i < 3; i++) {
			lookupFrom(ip(), ref, "3000000" + i).andExpect(status().isNotFound());                  // a different address every time
		}
		lookupFrom(ip(), ref, phone).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));   // even the right phone waits
		// the throttle is keyed by the reference whether or not it exists: an unknown one behaves the same
		for (int i = 0; i < 3; i++) {
			lookupFrom(ip(), "ANA-QQQQQQ", "3000000" + i).andExpect(status().isNotFound());
		}
		lookupFrom(ip(), "ANA-QQQQQQ", "30000009").andExpect(status().isTooManyRequests());
	}

	private org.springframework.test.web.servlet.ResultActions lookupFrom(String ip, String reference, String phone) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations/lookup").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType("application/json").content(json(Map.of("reference", reference, "phone", phone))));
	}
}
