package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.PaymentStatus;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.ReservationQuery;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.service.ReservationAdminService;
import com.anadepot.core.reservation.service.ReservationService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.security.Actors;
import com.anadepot.infra.web.PageResponse;
import com.anadepot.infra.web.QueryParams;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff reservation management ({@code /api/v1/admin/reservations}): list, search, detail, the lifecycle transitions and notes. Parsing and shaping only: the
 * transitions run in {@code ReservationService} (stock, audit and outbox in one transaction). Payments: {@code /api/v1/admin/payments}.
 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
class AdminReservationController {

	private final ReservationAdminService admin;
	private final ReservationService reservations;
	private final com.anadepot.core.payment.service.PaymentAdminService payments;

	AdminReservationController(ReservationAdminService admin, ReservationService reservations, com.anadepot.core.payment.service.PaymentAdminService payments) {
		this.payments = payments;
		this.admin = admin;
		this.reservations = reservations;
	}

	@GetMapping
	PageResponse<AdminReservationDtos.Summary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
			@RequestParam(required = false) String paymentStatus, @RequestParam(required = false) String currency, @RequestParam(required = false) String q,
			@RequestParam(required = false) UUID productId, @RequestParam(required = false) UUID customerId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String sort) {
		var query = new ReservationQuery(QueryParams.enumOf(ReservationStatus.class, status, "status"), QueryParams.enumOf(PaymentStatus.class, paymentStatus, "paymentStatus"),
				QueryParams.enumOf(SupportedCurrency.class, currency, "currency"), q, productId, customerId, instant(from, "from"), instant(to, "to"),
				QueryParams.enumOf(ReservationQuery.Sort.class, sort, "sort"));
		return PageResponse.of(admin.list(query, new PageRequest(page, size)), AdminReservationDtos::summary);
	}

	@GetMapping("/{id}")
	AdminReservationDtos.Detail get(@PathVariable UUID id) {
		return detail(admin.get(id));
	}

	@PostMapping("/{id}/accept")
	AdminReservationDtos.Detail accept(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
		return detail(reservations.accept(id, Actors.staff(authentication), key));
	}

	@PostMapping("/{id}/reject")
	AdminReservationDtos.Detail reject(@PathVariable UUID id, @RequestBody AdminReservationDtos.RejectRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "required")));
		}
		requireShort("note", body.note(), 1000);
		return detail(reservations.reject(id, Actors.staff(authentication), QueryParams.enumOf(RejectReason.class, body.reason(), "reason"), body.note(), key));
	}

	@PostMapping("/{id}/ready")
	AdminReservationDtos.Detail ready(@PathVariable UUID id, @RequestBody(required = false) AdminReservationDtos.ReadyRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		return detail(reservations.markReady(id, Actors.staff(authentication), body == null ? null : body.expiresAt(), key));
	}

	@PostMapping("/{id}/collect")
	AdminReservationDtos.Detail collect(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
		return detail(reservations.collect(id, Actors.staff(authentication), key));
	}

	@PostMapping("/{id}/cancel")
	AdminReservationDtos.Detail cancel(@PathVariable UUID id, @RequestBody AdminReservationDtos.CancelRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "required")));
		}
		requireShort("reason", body.reason(), 1000);
		return detail(reservations.cancel(id, Actors.staff(authentication), body.reason(), key));
	}

	@PostMapping("/{id}/notes")
	ResponseEntity<AdminReservationDtos.Detail> addNote(@PathVariable UUID id, @RequestBody AdminReservationDtos.NoteRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.visibility() == null || body.visibility().isBlank()) {
			issues.add(new FieldIssue("visibility", "required"));
		}
		if (body == null || body.body() == null || body.body().isBlank()) {
			issues.add(new FieldIssue("body", "required"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var visibility = QueryParams.enumOf(NoteVisibility.class, body.visibility(), "visibility");
		return ResponseEntity.status(HttpStatus.CREATED).body(detail(admin.addNote(id, visibility, body.body(), Actors.staff(authentication), key)));
	}

	private static void requireShort(String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw new FieldValidationException(List.of(new FieldIssue(field, "too_long")));
		}
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

	/** The staff view of one reservation, with the D1 "refund due" figures (card money to give back after rejection, cancellation or expiry). */
	private AdminReservationDtos.Detail detail(com.anadepot.core.reservation.model.Reservation r) {
		return AdminReservationDtos.detail(r, payments.refundDue(r));
	}
}
