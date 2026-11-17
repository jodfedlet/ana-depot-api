package com.anadepot.core.reservation.repository;

import com.anadepot.core.reservation.model.ItemKey;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

	Reservation save(Reservation reservation);

	Optional<Reservation> findById(UUID id);

	/** Loads the reservation holding a row lock until the transaction ends: serialises concurrent transitions and payments. */
	Optional<Reservation> findByIdForUpdate(UUID id);

	Optional<Reservation> findByReference(String reference);

	boolean referenceExists(String reference);

	/** Staff listing: every status, filters combined with AND (see {@link com.anadepot.core.reservation.model.ReservationQuery}). */
	PageResult<Reservation> findPage(com.anadepot.core.reservation.model.ReservationQuery query, PageRequest page);

	/** A customer's reservations, newest first. */
	PageResult<Reservation> findByCustomerId(UUID customerId, PageRequest page);

	/** Sum of quantities held by OPEN reservations for one stock position (used to reconcile {@code inventory_items.reserved}). */
	int openHeldQuantity(ItemKey key);

	/** The reference of each reservation id (unknown ids are absent). */
	java.util.Map<UUID, String> referencesOf(java.util.Collection<UUID> ids);
}
