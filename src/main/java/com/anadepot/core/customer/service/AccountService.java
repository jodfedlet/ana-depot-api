package com.anadepot.core.customer.service;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed-in customer's own data. Everything is keyed by the identity-provider user id taken from the verified token; there is
 * no way to name another customer. Guest reservations made before signing in are NOT shown here: guests are not merged into
 * accounts automatically (that would let anyone who knows a phone number claim the history; a verified-claim process is an open decision).
 */
public interface AccountService {

	/** The user's own customer record, if they have one yet (created by saving the profile or by their first signed-in reservation). */
	Optional<Customer> profile(UUID userId);

	/** Creates or updates the user's own customer record. Does not touch any reservation (those keep their own contact snapshot). */
	Customer saveProfile(UUID userId, Contact contact);

	/** The user's own reservations, newest first. */
	PageResult<Reservation> reservations(UUID userId, PageRequest page);

	/** One of the user's own reservations; another customer's reservation is reported as not found, exactly like a missing one. */
	Reservation reservation(UUID userId, UUID reservationId);
}
