package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D6 (owner-approved): reminds the customer shortly before the pickup deadline and expires ready reservations that passed it (stock is released, the customer is told, payments stay
 * on the ledger and the staff screen shows "refund due" for card money). Safe on every instance: each transition locks its reservation row and a second pass finds nothing to do.
 */
@Component
class ReservationExpiryScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

	private final ReservationService reservations;
	private final ReservationPolicyProperties properties;

	ReservationExpiryScheduler(ReservationService reservations, ReservationPolicyProperties properties) {
		this.reservations = reservations;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "${ana.reservation.expiry-interval:PT5M}")
	void run() {
		if (properties.expiryEnabled()) {
			runOnce();
		}
	}

	/** One pass (tests call this directly). */
	void runOnce() {
		try {
			int reminded = reservations.remindExpiring();
			int expired = reservations.expireOverdue();
			if (reminded > 0 || expired > 0) {
				log.info("Reservation expiry pass: {} reminder(s) queued, {} reservation(s) expired", reminded, expired);
			}
		} catch (RuntimeException e) {
			log.warn("Reservation expiry pass failed ({}); it is retried at the next interval", e.getClass().getSimpleName());
		}
	}
}
