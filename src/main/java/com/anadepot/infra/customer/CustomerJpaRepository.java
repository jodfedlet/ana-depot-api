package com.anadepot.infra.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {

	/** Guest rows only (no account): registered customers never match by phone. */
	Optional<CustomerEntity> findByDialCodeAndPhoneAndAuthUserIdIsNull(String dialCode, String phone);

	Optional<CustomerEntity> findByAuthUserId(UUID authUserId);
}
