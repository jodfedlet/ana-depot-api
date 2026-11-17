package com.anadepot.core.customer.model;

import java.time.Instant;

/** How often and when a customer reserved. */
public record CustomerActivity(long reservations, Instant firstReservationAt, Instant lastReservationAt) {

	public static CustomerActivity none() {
		return new CustomerActivity(0, null, null);
	}
}
