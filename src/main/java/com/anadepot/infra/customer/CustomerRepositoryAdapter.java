package com.anadepot.infra.customer;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.repository.CustomerRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class CustomerRepositoryAdapter implements CustomerRepository {

	private final CustomerJpaRepository jpa;

	CustomerRepositoryAdapter(CustomerJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public Customer save(Customer c) {
		var e = jpa.findById(c.id()).orElseGet(CustomerEntity::new);
		e.id = c.id();
		e.firstName = c.firstName();
		e.lastName = c.lastName();
		e.dialCode = c.dialCode();
		e.phone = c.phone();
		e.email = c.email();
		e.authUserId = c.authUserId();
		e.createdAt = c.createdAt();
		e.updatedAt = java.time.Instant.now();
		return toDomain(jpa.save(e));
	}

	@Override
	public Optional<Customer> findByPhone(String dialCode, String normalizedPhone) {
		return jpa.findByDialCodeAndPhone(dialCode, normalizedPhone).map(CustomerRepositoryAdapter::toDomain);
	}

	private static Customer toDomain(CustomerEntity e) {
		return new Customer(e.id, e.firstName, e.lastName, e.dialCode, e.phone, e.email, e.authUserId, e.createdAt);
	}
}
