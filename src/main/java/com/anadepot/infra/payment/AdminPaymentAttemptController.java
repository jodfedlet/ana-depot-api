package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.AttemptStatus;
import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.payment.service.OnlinePaymentService;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.infra.web.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff view of online payment attempts (work records, not money: money is in /admin/payments). No checkout URLs and no secrets. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminPaymentAttemptController {

	private final OnlinePaymentService online;

	AdminPaymentAttemptController(OnlinePaymentService online) {
		this.online = online;
	}

	record AttemptResponse(UUID id, UUID reservationId, String currency, String amount, String provider, String status, String providerSessionId, String providerPaymentIntentId,
			Instant expiresAt, UUID paymentId, boolean requiresAttention, String attentionReason, String failureCode, Instant createdAt, Instant updatedAt) {
		static AttemptResponse of(PaymentAttempt a) {
			return new AttemptResponse(a.id(), a.reservationId(), a.amount().currency().name(), a.amount().amount().toPlainString(), "STRIPE", a.status().name(), a.sessionId(),
					a.paymentIntentId(), a.expiresAt(), a.paymentId(), a.requiresAttention(), a.attentionReason(), a.failureCode(), a.createdAt(), a.updatedAt());
		}
	}

	@GetMapping("/payment-attempts")
	PageResponse<AttemptResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID reservationId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Boolean requiresAttention) {
		var s = com.anadepot.infra.web.QueryParams.enumOf(AttemptStatus.class, status, "status");
		var result = online.attempts(reservationId, s, requiresAttention, new PageRequest(page, size));
		return PageResponse.of(result, AttemptResponse::of);
	}
}
