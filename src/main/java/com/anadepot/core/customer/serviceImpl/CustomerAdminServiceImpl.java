package com.anadepot.core.customer.serviceImpl;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.model.CustomerActivity;
import com.anadepot.core.customer.model.CustomerAdminView;
import com.anadepot.core.customer.model.CustomerQuery;
import com.anadepot.core.customer.repository.CustomerRepository;
import com.anadepot.core.customer.service.CustomerAdminService;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class CustomerAdminServiceImpl implements CustomerAdminService {

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private final CustomerRepository customers;
	private final ReservationRepository reservations;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;

	public CustomerAdminServiceImpl(CustomerRepository customers, ReservationRepository reservations, AuditRecorder audit, TransactionBoundary tx) {
		this.customers = customers;
		this.reservations = reservations;
		this.audit = audit;
		this.tx = tx;
	}

	@Override
	public PageResult<CustomerAdminView> list(CustomerQuery query, PageRequest page) {
		return tx.inTransaction(() -> {
			var result = customers.findPage(query, page);
			var activity = reservations.activityOf(result.items().stream().map(Customer::id).toList());
			return new PageResult<>(result.items().stream().map(c -> view(c, activity.getOrDefault(c.id(), CustomerActivity.none()), false)).toList(), result.page(), result.size(), result.totalItems());
		});
	}

	@Override
	public CustomerAdminView get(UUID id) {
		return tx.inTransaction(() -> {
			var customer = find(id);
			return view(customer, reservations.activityOf(List.of(id)).getOrDefault(id, CustomerActivity.none()), true);
		});
	}

	@Override
	public CustomerAdminView updateContact(UUID id, long expectedVersion, String firstName, String lastName, String email, Actor actor) {
		var issues = new ArrayList<FieldIssue>();
		if (firstName == null || firstName.isBlank()) {
			issues.add(new FieldIssue("firstName", "required"));
		} else if (firstName.strip().length() > 100) {
			issues.add(new FieldIssue("firstName", "too_long"));
		}
		if (lastName == null || lastName.isBlank()) {
			issues.add(new FieldIssue("lastName", "required"));
		} else if (lastName.strip().length() > 100) {
			issues.add(new FieldIssue("lastName", "too_long"));
		}
		var cleanEmail = email == null || email.isBlank() ? null : email.strip();
		if (cleanEmail != null && (cleanEmail.length() > 254 || !EMAIL.matcher(cleanEmail).matches())) {
			issues.add(new FieldIssue("email", "invalid"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return tx.inTransaction(() -> {
			var before = find(id);
			if (before.registered()) {
				throw new DomainRuleViolation("customer_managed_by_owner", "A registered customer edits their own profile");
			}
			var meta = customers.meta(id).orElseThrow();
			if (meta.version() != expectedVersion) {
				throw new DomainRuleViolation("concurrent_update", "The customer was changed by someone else (version " + meta.version() + "). Reload it and try again.");
			}
			var next = new Customer(before.id(), firstName.strip(), lastName.strip(), before.dialCode(), before.phone(), cleanEmail, null, before.createdAt());
			var changed = new ArrayList<String>();
			if (!next.firstName().equals(before.firstName())) {
				changed.add("firstName");
			}
			if (!next.lastName().equals(before.lastName())) {
				changed.add("lastName");
			}
			if (!java.util.Objects.equals(next.email(), before.email())) {
				changed.add("email");
			}
			if (!changed.isEmpty()) {
				customers.save(next);
				audit.record(AuditEvent.of("CUSTOMER_UPDATED", "customer", id, actor, Map.of(), Map.of("changedFields", changed), null));   // field names only: values are personal data
			}
			return view(find(id), reservations.activityOf(List.of(id)).getOrDefault(id, CustomerActivity.none()), true);
		});
	}

	private Customer find(UUID id) {
		return customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
	}

	private CustomerAdminView view(Customer c, CustomerActivity activity, boolean withSummary) {
		var meta = customers.meta(c.id()).orElseThrow();
		return new CustomerAdminView(c, meta.version(), meta.updatedAt(), activity, withSummary ? reservations.summaryOf(c.id()) : null);
	}
}
