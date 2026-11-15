package com.anadepot.core.customer.repository;

import com.anadepot.core.customer.model.Customer;
import java.util.Optional;

public interface CustomerRepository {

	Customer save(Customer customer);

	Optional<Customer> findByPhone(String dialCode, String normalizedPhone);
}
