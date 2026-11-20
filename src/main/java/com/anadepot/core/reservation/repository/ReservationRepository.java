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

	/** Reservation count and first/last reservation time per customer id (customers without reservations are absent). */
	java.util.Map<UUID, com.anadepot.core.customer.model.CustomerActivity> activityOf(java.util.Collection<UUID> customerIds);

	/** What one customer's reservations add up to, currency by currency (see {@link com.anadepot.core.customer.model.CustomerSummary}). */
	com.anadepot.core.customer.model.CustomerSummary summaryOf(UUID customerId);

	/** Ready reservations whose pickup deadline has passed, oldest deadline first (the system expires them). */
	java.util.List<java.util.UUID> findReadyPastDeadline(java.time.Instant now, int limit);

	/** Ready reservations whose deadline falls within {@code cutoff} but has not passed, and that were not reminded yet. */
	java.util.List<java.util.UUID> findReadyNeedingReminder(java.time.Instant now, java.time.Instant cutoff, int limit);
}
