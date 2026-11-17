package com.anadepot.infra.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.reservation.service.ReservationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** No terms version configured: guest reservations still work; the acceptance time is recorded and the version stays null (nothing is invented). */
class TermsWithoutVersionApiIntegrationTest extends ReservationHttpSupport {

	@Autowired ReservationService reservations;

	@org.junit.jupiter.api.BeforeEach
	void pickup() {
		ensurePickupAvailable();
	}

	@Test
	void withoutAConfiguredVersionTheTimeIsStoredAndTheVersionIsNull() throws Exception {
		var product = stockedMattress(5);
		var created = reserve(request(freshPhone(), line(product, 1, "HTG")), null);
		var stored = reservations.get(UUID.fromString(created.get("id").asString()));
		assertThat(stored.termsVersion()).isNull();
		assertThat(stored.termsAcceptedAt()).isNotNull();
	}
}
