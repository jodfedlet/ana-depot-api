package com.anadepot.infra.customer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {

	/** Guest rows only (no account): registered customers never match by phone. */
	Optional<CustomerEntity> findByDialCodeAndPhoneAndAuthUserIdIsNull(String dialCode, String phone);

	Optional<CustomerEntity> findByAuthUserId(UUID authUserId);

	/** Name and contact e-mail of registered accounts (one query for a whole list). */
	record AccountName(UUID authUserId, String firstName, String lastName, String email) {
	}

	@Query("select new com.anadepot.infra.customer.CustomerJpaRepository$AccountName(c.authUserId, c.firstName, c.lastName, c.email) from CustomerEntity c where c.authUserId in :ids")
	List<AccountName> findNamesByAuthUserIds(@Param("ids") Collection<UUID> ids);
}
