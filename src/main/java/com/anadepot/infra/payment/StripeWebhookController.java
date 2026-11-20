package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.ProviderEvent;
import com.anadepot.core.payment.service.CheckoutGateway;
import com.anadepot.core.payment.service.OnlinePaymentService;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe webhook receiver. The signature is the ONLY authentication: it is verified against the RAW body with the endpoint's signing secret before
 * anything is parsed or stored. Stripe delivers at least once and out of order; {@link OnlinePaymentService#handle} absorbs both. A processing failure answers
 * 500 so Stripe retries. Nothing from the payload is logged.
 */
@RestController
class StripeWebhookController {

	private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
	private static final Map<String, ProviderEvent.Kind> KINDS = Map.of(
			"checkout.session.completed", ProviderEvent.Kind.SESSION_COMPLETED,
			"checkout.session.async_payment_succeeded", ProviderEvent.Kind.SESSION_ASYNC_SUCCEEDED,
			"checkout.session.async_payment_failed", ProviderEvent.Kind.SESSION_ASYNC_FAILED,
			"checkout.session.expired", ProviderEvent.Kind.SESSION_EXPIRED,
			"charge.refunded", ProviderEvent.Kind.CHARGE_REFUNDED,
			"payment_intent.payment_failed", ProviderEvent.Kind.PAYMENT_FAILED,
			"payment_intent.succeeded", ProviderEvent.Kind.PAYMENT_SUCCEEDED);

	private final OnlinePaymentService online;
	private final CheckoutGateway gateway;
	private final StripeProperties config;
	private final JsonMapper json = JsonMapper.builder().build();

	StripeWebhookController(OnlinePaymentService online, CheckoutGateway gateway, StripeProperties properties) {
		this.online = online;
		this.gateway = gateway;
		this.config = properties;
	}

	@PostMapping("/api/v1/webhooks/stripe")
	ResponseEntity<Void> receive(@RequestBody byte[] raw, @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
		if (!gateway.enabled()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		var payload = new String(raw, StandardCharsets.UTF_8);
		if (signature == null || signature.isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		try {
			Webhook.constructEvent(payload, signature, config.webhookSecret().strip());
		} catch (SignatureVerificationException | RuntimeException e) {
			log.warn("Stripe webhook rejected: signature could not be verified");
			return ResponseEntity.badRequest().build();
		}
		var event = parse(payload);
		if (event.isPresent()) {
			online.handle(event.get());
		}
		return ResponseEntity.ok().build();
	}

	/** The business-relevant part of a verified event; empty for event types this integration does not use. */
	Optional<ProviderEvent> parse(String payload) {
		var root = json.readTree(payload);
		var kind = KINDS.get(text(root, "type"));
		if (kind == null) {
			return Optional.empty();
		}
		var obj = root.path("data").path("object");
		if (kind == ProviderEvent.Kind.CHARGE_REFUNDED) {
			return Optional.of(refund(root, obj));
		}
		var isIntent = kind == ProviderEvent.Kind.PAYMENT_FAILED || kind == ProviderEvent.Kind.PAYMENT_SUCCEEDED;
		var currency = text(obj, "currency");
		var minor = isIntent ? obj.path("amount_received").isNumber() ? obj.path("amount_received") : obj.path("amount") : obj.path("amount_total");
		BigDecimal amount = null;
		if (minor.isNumber() && currency != null) {
			try {
				amount = BigDecimal.valueOf(minor.asLong()).movePointLeft(SupportedCurrency.parse(currency.toUpperCase()).fractionDigits());
			} catch (RuntimeException unsupported) {
				amount = null; // a currency we do not sell in: the amount cannot match any attempt
			}
		}
		var attemptId = text(obj.path("metadata"), "attempt_id");
		if (attemptId == null) {
			attemptId = text(obj, "client_reference_id");
		}
		var failure = text(obj.path("last_payment_error"), "code");
		return Optional.of(new ProviderEvent(text(root, "id"), kind, isIntent ? null : text(obj, "id"), isIntent ? text(obj, "id") : text(obj, "payment_intent"), attemptId, amount,
				currency, isIntent ? kind == ProviderEvent.Kind.PAYMENT_SUCCEEDED : "paid".equals(text(obj, "payment_status")), failure));
	}

	/** A Stripe charge object: {@code amount_refunded} is cumulative, {@code payment_intent} links it to our attempt. */
	private ProviderEvent refund(JsonNode root, JsonNode charge) {
		var currency = text(charge, "currency");
		BigDecimal refunded = null;
		if (charge.path("amount_refunded").isNumber() && currency != null) {
			try {
				refunded = BigDecimal.valueOf(charge.path("amount_refunded").asLong()).movePointLeft(SupportedCurrency.parse(currency.toUpperCase()).fractionDigits());
			} catch (RuntimeException unsupported) {
				refunded = null;
			}
		}
		return new ProviderEvent(text(root, "id"), ProviderEvent.Kind.CHARGE_REFUNDED, null, text(charge, "payment_intent"), null, refunded, currency, false, null);
	}

	private static String text(JsonNode node, String field) {
		var v = node.path(field);
		return v.isString() && !v.asString().isBlank() ? v.asString() : null;
	}
}
