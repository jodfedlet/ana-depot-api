package com.anadepot.core.customer.serviceImpl;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.core.customer.service.AccountService;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class AccountServiceImpl implements AccountService {

	private final CustomerRepository customers;
	private final ReservationRepository reservations;
	private final TransactionBoundary tx;
	private final Clock clock;

	public AccountServiceImpl(CustomerRepository customers, ReservationRepository reservations, TransactionBoundary tx, Clock clock) {
		this.customers = customers;
		this.reservations = reservations;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public Optional<Customer> profile(UUID userId) {
		return customers.findByAuthUserId(userId);
	}

	@Override
	public Customer saveProfile(UUID userId, Contact contact) {
		return tx.inTransaction(() -> {
			var phone = Customer.normalizePhone(contact.phone());
			var existing = customers.findByAuthUserId(userId);
			var id = existing.map(Customer::id).orElseGet(UUID::randomUUID);
			var createdAt = existing.map(Customer::createdAt).orElseGet(clock::instant);
			return customers.save(new Customer(id, contact.firstName(), contact.lastName(), contact.dialCode(), phone, contact.email(), userId, createdAt));
		});
	}

	@Override
	public PageResult<Reservation> reservations(UUID userId, PageRequest page) {
		return customers.findByAuthUserId(userId)
				.map(c -> reservations.findByCustomerId(c.id(), page))
				.orElseGet(() -> new PageResult<>(List.of(), page.page(), page.size(), 0));
	}

	@Override
	public Reservation reservation(UUID userId, UUID reservationId) {
		var customer = customers.findByAuthUserId(userId);
		return reservations.findById(reservationId)
				.filter(r -> customer.isPresent() && customer.get().id().equals(r.customerId()))
				.orElseThrow(() -> new NotFoundException("Reservation", reservationId));
	}
}
