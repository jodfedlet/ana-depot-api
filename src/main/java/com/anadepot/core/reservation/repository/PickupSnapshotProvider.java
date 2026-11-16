package com.anadepot.core.reservation.repository;

/**
 * Supplies the pickup information to freeze on a new reservation. The implementation reads the CURRENT pickup settings and refuses
 * ({@code pickup_disabled} / {@code pickup_not_configured}) when pickup cannot accept reservations. Later changes to the settings
 * never rewrite a snapshot already stored on a reservation.
 */
public interface PickupSnapshotProvider {

	String currentSnapshotJson();
}
