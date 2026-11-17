package com.anadepot.infra.customer;

import com.anadepot.core.customer.model.CustomerAdminView;
import com.anadepot.core.customer.model.CustomerQuery;
import com.anadepot.core.customer.service.CustomerAdminService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.Money;
import com.anadepot.infra.security.Actors;
import com.anadepot.infra.web.PageResponse;
import com.anadepot.infra.web.QueryParams;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff customers ({@code /api/v1/admin/customers}). A customer's reservations: {@code GET /api/v1/admin/reservations?customerId=}. Money figures are always per
 * currency: HTG and USD are never summed.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
class AdminCustomerController {

	record ContactRequest(Long version, String firstName, String lastName, String email) {
	}

	record Activity(long reservations, Instant firstReservationAt, Instant lastReservationAt) {
	}

	record Summary(Map<String, Long> reservationsByStatus, Map<String, String> collectedByCurrency, Map<String, String> openDueByCurrency) {
	}

	record Customer(UUID id, String kind, String firstName, String lastName, String dialCode, String phone, String email, boolean hasAccount, Instant createdAt, Instant updatedAt, long version,
			Activity activity, Summary summary) {
	}

	private final CustomerAdminService customers;

	AdminCustomerController(CustomerAdminService customers) {
		this.customers = customers;
	}

	@GetMapping
	PageResponse<Customer> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String kind, @RequestParam(required = false) String q,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String sort) {
		var query = new CustomerQuery(QueryParams.enumOf(CustomerQuery.Kind.class, kind, "kind"), q, instant(from, "from"), instant(to, "to"), QueryParams.enumOf(CustomerQuery.Sort.class, sort, "sort"));
		return PageResponse.of(customers.list(query, new PageRequest(page, size)), AdminCustomerController::view);
	}

	@GetMapping("/{id}")
	Customer get(@PathVariable UUID id) {
		return view(customers.get(id));
	}

	@PutMapping("/{id}")
	Customer update(@PathVariable UUID id, @RequestBody ContactRequest body, Authentication authentication) {
		if (body == null || body.version() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("version", "required")));
		}
		return view(customers.updateContact(id, body.version(), body.firstName(), body.lastName(), body.email(), Actors.staff(authentication)));
	}

	private static Customer view(CustomerAdminView v) {
		var c = v.customer();
		var activity = new Activity(v.activity().reservations(), v.activity().firstReservationAt(), v.activity().lastReservationAt());
		Summary summary = null;
		if (v.summary() != null) {
			var byStatus = new LinkedHashMap<String, Long>();
			v.summary().reservationsByStatus().forEach((k, n) -> byStatus.put(k.name(), n));
			summary = new Summary(byStatus, money(v.summary().collected()), money(v.summary().openDue()));
		}
		return new Customer(c.id(), c.registered() ? "REGISTERED" : "GUEST", c.firstName(), c.lastName(), c.dialCode(), c.phone(), c.email(), c.registered(), c.createdAt(), v.updatedAt(), v.version(), activity,
				summary);
	}

	private static Map<String, String> money(Map<com.anadepot.core.shared.money.SupportedCurrency, Money> amounts) {
		var out = new LinkedHashMap<String, String>();
		amounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.put(e.getKey().name(), e.getValue().amount().toPlainString()));
		return out;
	}

	private static Instant instant(String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return value.strip().length() == 10 ? Instant.parse(value.strip() + "T00:00:00Z") : Instant.parse(value.strip());
		} catch (DateTimeParseException e) {
			throw new FieldValidationException(List.of(new FieldIssue(field, "invalid")));
		}
	}
}
