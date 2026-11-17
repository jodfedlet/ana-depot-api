package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.model.PaymentQuery;
import com.anadepot.core.payment.model.PaymentView;
import com.anadepot.core.payment.service.PaymentAdminService;
import com.anadepot.core.payment.service.PaymentService;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.service.ReservationAdminService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.security.Actors;
import com.anadepot.infra.web.PageResponse;
import com.anadepot.infra.web.QueryParams;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * Staff payments ({@code /api/v1/admin}): cash received at pickup, reversals, adjustments, the ledger and its reconciliation. A payment settles only its own
 * currency; amounts of different currencies are never added. Every write REQUIRES an {@code Idempotency-Key}. History is append-only: corrections are new entries.
 * Who may record payments and the partial/over-payment policy are open business decisions: nothing is invented here (an over-payment is accepted as before).
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminPaymentController {

	record RecordRequest(String amount, String currency, String method, String receivedAt, String reference, String note) {
	}

	record ReverseRequest(String reason) {
	}

	record AdjustRequest(String amount, String currency, String reason) {
	}

	record Reversal(Instant at, String reason, UUID by) {
	}

	record PaymentResponse(UUID id, UUID reservationId, String reservationReference, String amount, String currency, String method, Instant receivedAt, Instant recordedAt, UUID recordedBy,
			String reference, String note, boolean reversed, Reversal reversal) {
	}

	record BalanceResponse(String currency, String total, String paid, String due) {
	}

	/** The payment and where the reservation stands afterwards. */
	record Outcome(PaymentResponse payment, String paymentStatus, List<BalanceResponse> balances) {
	}

	record LedgerResponse(UUID id, String type, String amount, String currency, UUID paymentId, UUID reversesEntryId, String reason, Instant at, UUID createdBy, String correlationId) {
	}

	record ReservationPayments(String paymentStatus, List<BalanceResponse> balances, List<PaymentResponse> payments) {
	}

	record Reconciliation(UUID reservationId, boolean consistent, List<ReconciliationLine> lines) {
	}

	record ReconciliationLine(String currency, String cachedPaid, String ledgerPaid, boolean consistent) {
	}

	private final PaymentService payments;
	private final PaymentAdminService admin;
	private final ReservationAdminService reservations;

	AdminPaymentController(PaymentService payments, PaymentAdminService admin, ReservationAdminService reservations) {
		this.payments = payments;
		this.admin = admin;
		this.reservations = reservations;
	}

	@GetMapping("/payments")
	PageResponse<PaymentResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID reservationId,
			@RequestParam(required = false) String currency, @RequestParam(required = false) String method, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) Boolean reversed, @RequestParam(required = false) UUID recordedBy) {
		var query = new PaymentQuery(reservationId, QueryParams.enumOf(SupportedCurrency.class, currency, "currency"), QueryParams.enumOf(PaymentMethod.class, method, "method"),
				instant(from, "from"), instant(to, "to"), reversed, recordedBy);
		return PageResponse.of(admin.list(query, new PageRequest(page, size)), AdminPaymentController::payment);
	}

	@GetMapping("/payments/{id}")
	PaymentResponse get(@PathVariable UUID id) {
		return payment(admin.get(id));
	}

	@PostMapping("/payments/{id}/reversal")
	Outcome reverse(@PathVariable UUID id, @RequestBody ReverseRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
		if (body == null || body.reason() == null || body.reason().isBlank()) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "required")));
		}
		if (body.reason().length() > 1000) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "too_long")));
		}
		var payment = payments.reverse(id, body.reason(), Actors.staff(authentication), key);
		return outcome(admin.get(payment.id()));
	}

	@GetMapping("/reservations/{reservationId}/payments")
	ReservationPayments forReservation(@PathVariable UUID reservationId) {
		var list = admin.forReservation(reservationId);
		var reservation = reservations.get(reservationId);
		return new ReservationPayments(reservation.paymentStatus().name(), balances(reservation), list.stream().map(AdminPaymentController::payment).toList());
	}

	@PostMapping("/reservations/{reservationId}/payments")
	ResponseEntity<Outcome> record(@PathVariable UUID reservationId, @RequestBody RecordRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		var issues = new ArrayList<FieldIssue>();
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		var currency = currency(body.currency(), issues);
		var amount = currency == null ? null : money(body.amount(), currency, "amount", issues);
		if (amount != null && !amount.isPositive()) {
			issues.add(new FieldIssue("amount", "must_be_positive"));
		}
		var method = QueryParams.enumOf(PaymentMethod.class, body.method(), "method");
		var receivedAt = instant(body.receivedAt(), "receivedAt");
		if (body.reference() != null && body.reference().length() > 100) {
			issues.add(new FieldIssue("reference", "too_long"));
		}
		if (body.note() != null && body.note().length() > 1000) {
			issues.add(new FieldIssue("note", "too_long"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var payment = payments.record(new RecordPaymentCommand(reservationId, amount, method == null ? PaymentMethod.CASH_AT_PICKUP : method, receivedAt, Actors.staff(authentication),
				body.reference(), body.note(), key));
		return ResponseEntity.status(HttpStatus.CREATED).body(outcome(admin.get(payment.id())));
	}

	@GetMapping("/reservations/{reservationId}/ledger")
	List<LedgerResponse> ledger(@PathVariable UUID reservationId) {
		return admin.ledger(reservationId).stream().map(AdminPaymentController::ledger).toList();
	}

	@PostMapping("/reservations/{reservationId}/payment-adjustments")
	ResponseEntity<LedgerResponse> adjust(@PathVariable UUID reservationId, @RequestBody AdjustRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		var issues = new ArrayList<FieldIssue>();
		var currency = currency(body.currency(), issues);
		var amount = currency == null ? null : money(body.amount(), currency, "amount", issues);
		if (body.reason() == null || body.reason().isBlank()) {
			issues.add(new FieldIssue("reason", "required"));
		} else if (body.reason().length() > 1000) {
			issues.add(new FieldIssue("reason", "too_long"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(ledger(payments.adjust(reservationId, amount, body.reason(), Actors.staff(authentication), key)));
	}

	@GetMapping("/reservations/{reservationId}/reconciliation")
	Reconciliation reconciliation(@PathVariable UUID reservationId) {
		reservations.get(reservationId);   // 404 for an unknown reservation
		var report = payments.reconcile(reservationId);
		return new Reconciliation(report.reservationId(), report.consistent(), report.lines().stream()
				.map(l -> new ReconciliationLine(l.currency().name(), l.cachedPaid().amount().toPlainString(), l.ledgerPaid().amount().toPlainString(), l.consistent())).toList());
	}

	// ---- mapping and parsing ----

	private Outcome outcome(PaymentView view) {
		var reservation = reservations.get(view.payment().reservationId());
		return new Outcome(payment(view), reservation.paymentStatus().name(), balances(reservation));
	}

	private static List<BalanceResponse> balances(com.anadepot.core.reservation.model.Reservation r) {
		return r.balances().values().stream().map(b -> new BalanceResponse(b.total().currency().name(), b.total().amount().toPlainString(), b.paid().amount().toPlainString(), b.due().amount().toPlainString())).toList();
	}

	private static PaymentResponse payment(PaymentView v) {
		var p = v.payment();
		return new PaymentResponse(p.id(), p.reservationId(), v.reservationReference(), p.amount().amount().toPlainString(), p.amount().currency().name(), p.method().name(), p.receivedAt(),
				p.recordedAt(), p.recordedBy(), p.reference(), p.note(), v.reversed(), v.reversal() == null ? null : new Reversal(v.reversal().at(), v.reversal().reason(), v.reversal().createdBy()));
	}

	private static LedgerResponse ledger(LedgerEntry e) {
		return new LedgerResponse(e.id(), e.type().name(), e.amount().amount().toPlainString(), e.amount().currency().name(), e.paymentId(), e.reversesEntryId(), e.reason(), e.at(), e.createdBy(),
				e.correlationId());
	}

	private static SupportedCurrency currency(String code, List<FieldIssue> issues) {
		if (code == null || code.isBlank()) {
			issues.add(new FieldIssue("currency", "required"));
			return null;
		}
		var parsed = QueryParams.enumOf(SupportedCurrency.class, code, "currency");
		return parsed;
	}

	private static Money money(String text, SupportedCurrency currency, String field, List<FieldIssue> issues) {
		if (text == null || text.isBlank()) {
			issues.add(new FieldIssue(field, "required"));
			return null;
		}
		try {
			return Money.of(new BigDecimal(text.strip()), currency);
		} catch (NumberFormatException e) {
			issues.add(new FieldIssue(field, "invalid"));
		} catch (DomainRuleViolation e) {
			issues.add(new FieldIssue(field, "too_many_decimals"));
		}
		return null;
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
