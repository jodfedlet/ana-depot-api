package com.anadepot.core.reservation.model;

public enum ReservationStatus {
	PENDING_REVIEW, CONFIRMED, REJECTED, READY_FOR_PICKUP, COLLECTED, CANCELLED, EXPIRED;

	/** Open reservations hold stock (count as "reserved"). */
	public boolean isOpen() {
		return this == PENDING_REVIEW || this == CONFIRMED || this == READY_FOR_PICKUP;
	}
}
