package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.payment.service.OnlinePaymentService;
import com.anadepot.core.payment.service.OnlinePaymentService.ReservationAccess;
import com.anadepot.core.reservation.model.Balance;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer side of online payment (docs/payments-stripe.md). Nothing here decides that money arrived: {@code status} reads the database, which only a
 * verified provider webhook changes. The amount to pay is never accepted from the client.
 */
@RestController
class OnlinePaymentController {

	private final OnlinePaymentService online;
	private final com.anadepot.core.settings.service.StoreSettingsService settings;

	OnlinePaymentController(OnlinePaymentService online, com.anadepot.core.settings.service.StoreSettingsService settings) {
		this.online = online;
		this.settings = settings;
	}

	/**
	 * What payment options exist RIGHT NOW, decided by the backend (never by frontend configuration). {@code online}: the hosted card option, present only when AVAILABLE (kept for older
	 * clients). {@code onlineStatus}: AVAILABLE | DISABLED (switched off, or the account cannot charge any configured currency) | TEMPORARILY_UNAVAILABLE (switched on, provider not reachable).
	 * {@code atPickup.available}: reservations are open, so paying at pickup works. {@code summary}: BOTH | PICKUP_ONLY | NONE (NONE = reservations are closed, so nothing can be paid).
	 */
	record Options(OnlineOption online, String onlineStatus, AtPickup atPickup, String summary) {
	}

	record AtPickup(boolean available) {
	}

	record OnlineOption(String provider, List<String> currencies) {
	}

	record CheckoutRequest(String currency, String phone) {
	}

	record StatusRequest(String phone) {
	}

	record CheckoutResponse(UUID attemptId, String checkoutUrl, String currency, String amount, Instant expiresAt, boolean reused) {
	}

	record AttemptView(UUID id, String currency, String amount, String status, Instant createdAt, Instant settledAt) {
		static AttemptView of(PaymentAttempt a) {
			return new AttemptView(a.id(), a.amount().currency().name(), a.amount().amount().toPlainString(), a.status().name(), a.createdAt(),
					a.status() == com.anadepot.core.payment.model.AttemptStatus.SUCCEEDED ? a.updatedAt() : null);
		}
	}

	record BalanceView(String currency, String total, String paid, String due) {
		static BalanceView of(Balance b) {
			return new BalanceView(b.total().currency().name(), b.total().amount().toPlainString(), b.paid().amount().toPlainString(), b.due().amount().toPlainString());
		}
	}

	record ReservationState(String reference, String status, String paymentStatus, List<BalanceView> balances) {
	}

	record StatusResponse(ReservationState reservation, List<AttemptView> attempts) {
	}

	@GetMapping("/api/v1/store/payment-options")
	Options options() {
		var availability = online.availability();
		var currencies = availability == com.anadepot.core.payment.service.CheckoutGateway.Availability.AVAILABLE ? online.availableCurrencies().stream().map(Enum::name).sorted().toList() : List.<String>of();
		var pickup = settings.getPickup().availability() == com.anadepot.core.settings.model.PickupSettings.Availability.AVAILABLE;
		var summary = !pickup ? "NONE" : currencies.isEmpty() ? "PICKUP_ONLY" : "BOTH";
		return new Options(currencies.isEmpty() ? null : new OnlineOption("STRIPE", currencies), availability.name(), new AtPickup(pickup), summary);
	}

	@PostMapping("/api/v1/reservations/{reference}/payments/checkout-session")
	ResponseEntity<CheckoutResponse> checkout(@PathVariable String reference, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestBody CheckoutRequest body, Authentication authentication) {
		if (body == null || body.currency() == null || body.currency().isBlank()) {
			throw new FieldValidationException(List.of(new FieldIssue("currency", "required")));
		}
		SupportedCurrency currency;
		try {
			currency = SupportedCurrency.parse(body.currency().strip());
		} catch (DomainRuleViolation e) {
			throw new FieldValidationException(List.of(new FieldIssue("currency", "invalid")));
		}
		var result = online.createCheckout(access(reference, body.phone(), authentication), currency, key);
		var a = result.attempt();
		return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new CheckoutResponse(a.id(), a.checkoutUrl(), a.amount().currency().name(), a.amount().amount().toPlainString(), a.expiresAt(), result.reused()));
	}

	@PostMapping("/api/v1/reservations/{reference}/payments/status")
	StatusResponse status(@PathVariable String reference, @RequestBody(required = false) StatusRequest body, Authentication authentication) {
		var status = online.status(access(reference, body == null ? null : body.phone(), authentication));
		var r = status.reservation();
		var attempts = status.attempts().stream().map(AttemptView::of).toList();
		return new StatusResponse(new ReservationState(r.reference(), r.status().name(), r.paymentStatus().name(), r.balances().values().stream().map(BalanceView::of).toList()), attempts);
	}

	private static ReservationAccess access(String reference, String phone, Authentication authentication) {
		UUID userId = null;
		if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken) && authentication.isAuthenticated()) {
			userId = UUID.fromString(authentication.getName());
		}
		if (userId == null && (phone == null || phone.isBlank())) {
			throw new FieldValidationException(List.of(new FieldIssue("phone", "required")));
		}
		return new ReservationAccess(reference, phone, userId);
	}
}
