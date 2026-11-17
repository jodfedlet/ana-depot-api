package com.anadepot.infra.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.reservation.service.ReservationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** The acceptance of the reservation terms is persisted with its time and the configured version (the legal text itself is not part of the application). */
@TestPropertySource(properties = "ana.legal.terms-version=2026-11-v1")
class TermsAcknowledgementApiIntegrationTest extends ReservationHttpSupport {

	@Autowired ReservationService reservations;

	@org.junit.jupiter.api.BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	@Test
	void aSubmittedReservationRecordsTheConfiguredTermsVersionAndTheAcceptanceTime() throws Exception {
		var product = stockedMattress(5);
		var before = java.time.Instant.now().minusSeconds(2);
		var created = reserve(request(freshPhone(), line(product, 1, "HTG")), null);

		var stored = reservations.get(UUID.fromString(created.get("id").asString()));
		assertThat(stored.termsVersion()).isEqualTo("2026-11-v1");
		assertThat(stored.termsAcceptedAt()).isAfter(before).isBefore(java.time.Instant.now().plusSeconds(2));
	}

	@Test
	void aRequestThatDoesNotAcceptTheTermsIsRefusedAndNothingIsStored() throws Exception {
		var product = stockedMattress(5);
		var body = request(freshPhone(), line(product, 1, "HTG"));
		body.put("acknowledgedTerms", false);

		post("/api/v1/reservations", body, null, "key-" + UUID.randomUUID()).andExpect(status().isUnprocessableContent());
		assertThat(inventory.get(product.id(), null).reserved()).isZero();

		body.remove("acknowledgedTerms");
		post("/api/v1/reservations", body, null, "key-" + UUID.randomUUID()).andExpect(status().isUnprocessableContent());
	}

	@Test
	void theCustomerViewDoesNotExposeTheTermsBookkeeping() throws Exception {
		var product = stockedMattress(5);
		var created = reserve(request(freshPhone(), line(product, 1, "USD")), null);
		assertThat(created.has("termsVersion")).isFalse();
		assertThat(created.has("termsAcceptedAt")).isFalse();
	}
}
