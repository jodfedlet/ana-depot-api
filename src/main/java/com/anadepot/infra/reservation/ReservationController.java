package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.service.ReservationService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.infra.ratelimit.ClientIpResolver;
import com.anadepot.infra.ratelimit.RateLimitExceededException;
import com.anadepot.infra.ratelimit.RateLimiter;
import com.anadepot.infra.reservation.ReservationRequests.CreateReservationRequest;
import com.anadepot.infra.reservation.ReservationRequests.LookupRequest;
import com.anadepot.infra.security.Actors;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guest-friendly reservation endpoints (no account needed). A valid bearer token, when sent, makes the reservation the signed-in
 * customer's own; an invalid or expired token is a 401, not a silent downgrade to guest. Never takes prices from the client.
 */
@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController {

	private static final Pattern REFERENCE = Pattern.compile("ANA-[A-Z2-9]{6}");
	private static final String NOT_FOUND_CODE = "reservation_not_found";
	private static final String NOT_FOUND_MESSAGE = "No reservation matches this reference and phone number.";

	private final ReservationService reservations;
	private final RateLimiter limiter;
	private final com.anadepot.infra.config.AnaProperties properties;
	private final CustomerViews views;

	ReservationController(ReservationService reservations, RateLimiter limiter, com.anadepot.infra.config.AnaProperties properties, CustomerViews views) {
		this.views = views;
		this.reservations = reservations;
		this.limiter = limiter;
		this.properties = properties;
	}

	/** {@code Idempotency-Key} is REQUIRED: a customer on a poor connection retries, and must never create two reservations. */
	@PostMapping
	ResponseEntity<CustomerReservationView> submit(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateReservationRequest body, Authentication authentication) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new DomainRuleViolation("idempotency_key_required", "The Idempotency-Key header is required to create a reservation");
		}
		var command = ReservationRequests.toCommand(body, Actors.customerOrGuest(authentication), idempotencyKey).withTermsVersion(properties.legal().termsVersion());
		return ResponseEntity.status(HttpStatus.CREATED).body(CustomerReservationView.of(reservations.submit(command)));
	}

	/**
	 * POST (the phone number must not appear in a URL or access log). The reference alone is never enough. An unknown reference, a
	 * malformed reference and a wrong phone number all produce the SAME 404, so the endpoint cannot be used to find which references exist.
	 */
	@PostMapping("/lookup")
	CustomerReservationView lookup(@RequestBody LookupRequest body) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.reference() == null || body.reference().isBlank()) {
			issues.add(new FieldIssue("reference", "required"));
		} else if (body.reference().length() > 40) {
			issues.add(new FieldIssue("reference", "too_long"));
		}
		if (body == null || body.phone() == null || body.phone().isBlank()) {
			issues.add(new FieldIssue("phone", "required"));
		} else if (body.phone().length() > 40) {
			issues.add(new FieldIssue("phone", "too_long"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var reference = body.reference().strip().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
		if (!REFERENCE.matcher(reference).matches()) {
			throw notFound();
		}
		// a second throttle keyed by the reference itself, whoever asks and whether or not it exists (no oracle)
		var decision = limiter.check("reservation.lookup.reference", "ref:" + reference);
		if (!decision.allowed()) {
			throw new RateLimitExceededException(decision.retryAfter());
		}
		return reservations.lookup(reference, body.phone()).map(views::detail).orElseThrow(ReservationController::notFound);
	}

	record CancelRequest(String reference, String phone, String reason) {

		/** Personal text and a phone number: never printed. */
		@Override
		public String toString() {
			return "CancelRequest[redacted]";
		}
	}

	/**
	 * D7 (owner-approved): the customer cancels their own reservation until the store starts preparing it. The same ownership proof as {@code /lookup} (reference AND phone; an unknown
	 * reference, a malformed one and a wrong phone all answer the same 404, and the same per-reference throttle applies). A reason is required.
	 */
	@PostMapping("/cancel")
	CustomerReservationView cancel(@RequestBody CancelRequest body, Authentication authentication) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.reference() == null || body.reference().isBlank()) {
			issues.add(new FieldIssue("reference", "required"));
		} else if (body.reference().length() > 40) {
			issues.add(new FieldIssue("reference", "too_long"));
		}
		if (body == null || body.phone() == null || body.phone().isBlank()) {
			issues.add(new FieldIssue("phone", "required"));
		} else if (body.phone().length() > 40) {
			issues.add(new FieldIssue("phone", "too_long"));
		}
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			issues.add(new FieldIssue("reason", "required"));
		} else if (body.reason().strip().length() > 300) {
			issues.add(new FieldIssue("reason", "too_long"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var reference = body.reference().strip().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
		if (!REFERENCE.matcher(reference).matches()) {
			throw notFound();
		}
		var decision = limiter.check("reservation.lookup.reference", "ref:" + reference);
		if (!decision.allowed()) {
			throw new RateLimitExceededException(decision.retryAfter());
		}
		try {
			return views.detail(reservations.cancelByCustomer(reference, body.phone(), Actors.customerOrGuest(authentication), body.reason()));
		} catch (NotFoundException e) {
			throw notFound();   // whatever was not found (reference or phone), the answer is identical
		}
	}

	static NotFoundException notFound() {
		return NotFoundException.withCode(NOT_FOUND_CODE, NOT_FOUND_MESSAGE);
	}
}
