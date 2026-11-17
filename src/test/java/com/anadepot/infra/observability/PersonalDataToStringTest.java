package com.anadepot.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.reservation.model.Contact;
import com.anadepot.infra.reservation.ReservationRequests;
import org.junit.jupiter.api.Test;

/** A stray log statement or DEBUG logging of a request/record must never print a phone number, e-mail or name. */
class PersonalDataToStringTest {

	@Test
	void recordsThatHoldPersonalDataPrintNothingPersonal() {
		var contact = new Contact("Marie", "Jean-Baptiste", "+509", "37001234", "marie@example.test");
		assertThat(contact.toString()).doesNotContain("Marie", "Jean", "37001234", "marie@example.test");
		assertThat(new ReservationRequests.LookupRequest("ANA-ABC123", "37001234").toString()).doesNotContain("ANA-ABC123", "37001234");
	}
}
