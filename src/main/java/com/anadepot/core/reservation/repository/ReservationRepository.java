package com.anadepot.core.reservation.repository;

import com.anadepot.core.reservation.model.ItemKey;
import com.anadepot.core.reservation.model.Reservation;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

	Reservation save(Reservation reservation);

	Optional<Reservation> findById(UUID id);

	/** Loads the reservation holding a row lock until the transaction ends: serialises concurrent transitions and payments. */
	Optional<Reservation> findByIdForUpdate(UUID id);

	Optional<Reservation> findByReference(String reference);

	boolean referenceExists(String reference);

	/** Sum of quantities held by OPEN reservations for one stock position (used to reconcile {@code inventory_items.reserved}). */
	int openHeldQuantity(ItemKey key);
}
