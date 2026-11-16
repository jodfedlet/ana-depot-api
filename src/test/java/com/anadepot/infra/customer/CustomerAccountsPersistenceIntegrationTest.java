package com.anadepot.infra.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.infra.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** V9: a phone number is unique among GUESTS only; registered customers are separate records keyed by their identity-provider user. */
class CustomerAccountsPersistenceIntegrationTest extends AbstractIntegrationTest {

	@Autowired CustomerRepository customers;

	private static String phone() {
		return "4" + String.format("%07d", (int) (Math.random() * 10_000_000));
	}

	private static Customer customer(String phone, UUID authUserId) {
		return new Customer(UUID.randomUUID(), "A", "B", "+509", phone, null, authUserId, Instant.now());
	}

	@Test
	void twoGuestRecordsCannotShareAPhoneNumber() {
		var phone = phone();
		customers.save(customer(phone, null));
		assertThatThrownBy(() -> customers.save(customer(phone, null))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aRegisteredCustomerMayShareAPhoneNumberWithAGuestAndWithOtherAccounts_andNeverMatchesAsAGuest() {
		var phone = phone();
		var guest = customers.save(customer(phone, null));
		var alice = customers.save(customer(phone, UUID.randomUUID()));
		var bob = customers.save(customer(phone, UUID.randomUUID()));

		assertThat(java.util.Set.of(guest.id(), alice.id(), bob.id())).hasSize(3);
		assertThat(customers.findGuestByPhone("+509", phone).orElseThrow().id()).isEqualTo(guest.id());   // only the guest row is found by phone
		assertThat(alice.registered()).isTrue();
		assertThat(guest.registered()).isFalse();
		assertThat(customers.findByAuthUserId(alice.authUserId()).orElseThrow().id()).isEqualTo(alice.id());
	}

	@Test
	void anIdentityProviderUserOwnsExactlyOneCustomerRecord() {
		var user = UUID.randomUUID();
		customers.save(customer(phone(), user));
		assertThatThrownBy(() -> customers.save(customer(phone(), user))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aGuestPhoneHistoryStaysWithTheGuestWhenAnAccountUsesTheSameNumber() {
		var phone = phone();
		customers.save(customer(phone, UUID.randomUUID()));          // account first
		var guest = customers.save(customer(phone, null));           // then a guest with the same number: allowed, separate
		assertThat(customers.findGuestByPhone("+509", phone).orElseThrow().id()).isEqualTo(guest.id());
	}
}
