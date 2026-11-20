package com.anadepot.core.reservation.service;

import java.time.Duration;

/**
 * D6 (owner-approved): how long a READY reservation is held for pickup, and how long before the deadline the customer is reminded. The values come from the store's pickup
 * settings (hold period in days) with the approved default of three days, and from configuration; the core only asks.
 */
public interface HoldPolicy {

	/** From the moment the reservation became ready. */
	Duration holdPeriod();

	/** The reminder is sent this long before the deadline. */
	Duration reminderLead();
}
