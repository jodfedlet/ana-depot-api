package com.anadepot.core.customer.repository;

import com.anadepot.core.customer.model.Customer;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

	Customer save(Customer customer);

	/** The GUEST record (no account) for this phone number. Registered customers are never returned: they are not matched by phone. */
	Optional<Customer> findGuestByPhone(String dialCode, String normalizedPhone);

	/** The registered customer owned by this identity-provider user. */
	Optional<Customer> findByAuthUserId(UUID authUserId);

	Optional<Customer> findById(UUID id);

	record Meta(long version, java.time.Instant updatedAt) {
	}

	Optional<Meta> meta(UUID id);

	/** Staff listing (see {@link com.anadepot.core.customer.model.CustomerQuery}). */
	com.anadepot.core.shared.PageResult<Customer> findPage(com.anadepot.core.customer.model.CustomerQuery query, com.anadepot.core.shared.PageRequest page);
}
