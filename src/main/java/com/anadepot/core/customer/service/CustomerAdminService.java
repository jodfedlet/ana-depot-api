package com.anadepot.core.customer.service;

import com.anadepot.core.customer.model.CustomerAdminView;
import com.anadepot.core.customer.model.CustomerQuery;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.util.UUID;

/**
 * Staff view of customers. The phone number (a guest's identity) and the account link are never edited here, guests and accounts are never merged, and
 * the contact details copied onto past reservations are never changed.
 */
public interface CustomerAdminService {

	PageResult<CustomerAdminView> list(CustomerQuery query, PageRequest page);

	/** The customer with its reservation summary per currency. */
	CustomerAdminView get(UUID id);

	/**
	 * Corrects a GUEST customer's name and e-mail ({@code customer_managed_by_owner} for a registered customer: the owner edits their own profile).
	 * {@code expectedVersion} is the version the caller read. The audit entry records which fields changed, never their values.
	 */
	CustomerAdminView updateContact(UUID id, long expectedVersion, String firstName, String lastName, String email, Actor actor);
}
