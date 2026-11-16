package com.anadepot.infra.customer;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.service.AccountService;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.infra.reservation.CustomerReservationPage;
import com.anadepot.infra.reservation.CustomerReservationView;
import com.anadepot.infra.reservation.ReservationRequests;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own profile and reservations. The customer is ALWAYS the verified token's subject: no path or body
 * parameter can name another customer, and another customer's reservation is a 404 identical to a missing one.
 */
@RestController
@RequestMapping("/api/v1/me")
class AccountController {

	/** {@code id} is null until the profile has been saved (or a first reservation made while signed in); {@code email} then falls back to the account e-mail. */
	record ProfileResponse(UUID id, String firstName, String lastName, String dialCode, String phone, String email) {
	}

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/profile")
	ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
		return accounts.profile(userId(jwt)).map(AccountController::response).orElseGet(() -> new ProfileResponse(null, null, null, null, null, jwt.getClaimAsString("email")));
	}

	@PutMapping("/profile")
	ProfileResponse saveProfile(@AuthenticationPrincipal Jwt jwt, @RequestBody ReservationRequests.ProfileRequest body) {
		return response(accounts.saveProfile(userId(jwt), ReservationRequests.toContact(body)));
	}

	@GetMapping("/reservations")
	CustomerReservationPage reservations(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var result = accounts.reservations(userId(jwt), new PageRequest(page, size));
		return new CustomerReservationPage(result.items().stream().map(CustomerReservationView::of).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@GetMapping("/reservations/{id}")
	CustomerReservationView reservation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return CustomerReservationView.of(accounts.reservation(userId(jwt), id));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	private static ProfileResponse response(Customer c) {
		return new ProfileResponse(c.id(), c.firstName(), c.lastName(), c.dialCode(), c.phone(), c.email());
	}
}
