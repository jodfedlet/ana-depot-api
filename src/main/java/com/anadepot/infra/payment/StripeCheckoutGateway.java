package com.anadepot.infra.payment;

import com.anadepot.core.payment.service.CheckoutGateway;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stripe Checkout (hosted page) behind {@link CheckoutGateway}. Stripe objects never leave this class. The secret key is used only here.
 * Eligibility is asked of Stripe for the connected account (its country's supported payment currencies) AND restricted to the configured list;
 * if Stripe cannot be asked, no currency is offered (fail closed). There is no fallback currency and no conversion.
 */
@Component
class StripeCheckoutGateway implements CheckoutGateway {

	private static final Logger log = LoggerFactory.getLogger(StripeCheckoutGateway.class);
	private static final Duration POSITIVE_CACHE = Duration.ofMinutes(10);
	private static final Duration NEGATIVE_CACHE = Duration.ofMinutes(1);

	private final StripeProperties config;
	private final Clock clock;
	private final StripeClient client;
	private final Set<SupportedCurrency> configured;
	private final AtomicReference<Cached> eligibility = new AtomicReference<>();

	private record Cached(Set<SupportedCurrency> currencies, Instant until) {
	}

	StripeCheckoutGateway(StripeProperties properties, Clock clock) {
		this.config = properties;
		this.clock = clock;
		this.configured = parseCurrencies(config.enabledCurrencies());
		if (!config.enabled()) {
			this.client = null;
			return;
		}
		var problems = problems(config, configured);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Stripe is enabled but not configured correctly: " + String.join("; ", problems));
		}
		var builder = StripeClient.builder().setApiKey(config.secretKey().strip()).setConnectTimeout(5_000).setReadTimeout(15_000).setMaxNetworkRetries(2);
		if (config.apiBase() != null && !config.apiBase().isBlank()) {
			builder.setApiBase(config.apiBase().strip());
		}
		this.client = builder.build();
	}

	static Set<SupportedCurrency> parseCurrencies(java.util.List<String> raw) {
		var result = EnumSet.noneOf(SupportedCurrency.class);
		if (raw != null) {
			for (var item : raw) {
				Arrays.stream(item.split(",")).map(String::strip).filter(c -> !c.isEmpty()).forEach(c -> result.add(SupportedCurrency.parse(c)));
			}
		}
		return result;
	}

	static java.util.List<String> problems(StripeProperties config, Set<SupportedCurrency> currencies) {
		var problems = new java.util.ArrayList<String>();
		if (config.secretKey() == null || config.secretKey().isBlank()) {
			problems.add("STRIPE_SECRET_KEY is not set");
		}
		if (config.webhookSecret() == null || config.webhookSecret().isBlank()) {
			problems.add("STRIPE_WEBHOOK_SECRET is not set");
		}
		if (currencies.isEmpty()) {
			problems.add("ANA_STRIPE_ENABLED_CURRENCIES is empty");
		}
		if (!validReturnBase(config.returnBaseUrl())) {
			problems.add("ANA_STRIPE_RETURN_BASE_URL must be an https origin (http only for localhost)");
		}
		if (config.checkoutExpirationMinutes() < 30 || config.checkoutExpirationMinutes() > 1440) {
			problems.add("ANA_STRIPE_CHECKOUT_EXPIRATION_MINUTES must be between 30 and 1440");
		}
		return problems;
	}

	/** An origin only (no path, query, credentials); https, or http for loopback development. */
	static boolean validReturnBase(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			var u = URI.create(value.strip());
			var loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
			var schemeOk = "https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && loopback);
			var originOnly = u.getRawUserInfo() == null && (u.getRawPath() == null || u.getRawPath().isEmpty()) && u.getRawQuery() == null && u.getRawFragment() == null;
			return schemeOk && originOnly && u.getHost() != null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	public boolean enabled() {
		return client != null;
	}

	@Override
	public Set<SupportedCurrency> eligibleCurrencies() {
		if (client == null) {
			return Set.of();
		}
		var now = clock.instant();
		var cached = eligibility.get();
		if (cached != null && cached.until().isAfter(now)) {
			return cached.currencies();
		}
		Set<SupportedCurrency> result;
		try {
			var account = client.v1().accounts().retrieveCurrent();
			var spec = client.v1().countrySpecs().retrieve(account.getCountry());
			var supported = spec.getSupportedPaymentCurrencies().stream().map(c -> c.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
			result = EnumSet.noneOf(SupportedCurrency.class);
			for (var c : configured) {
				if (supported.contains(c.name())) {
					result.add(c);
				} else {
					log.warn("Stripe: {} is configured but not supported for the connected account's country; online payment stays off for it", c);
				}
			}
			eligibility.set(new Cached(Set.copyOf(result), now.plus(POSITIVE_CACHE)));
			return Set.copyOf(result);
		} catch (StripeException | RuntimeException e) {
			log.warn("Stripe: currency eligibility could not be checked ({}); online payment is offered in no currency", e.getClass().getSimpleName());
			eligibility.set(new Cached(Set.of(), now.plus(NEGATIVE_CACHE)));
			return Set.of();
		}
	}

	/** Stripe's published minimum charge (docs.stripe.com/currencies); unknown currencies are left to Stripe's own validation. */
	@Override
	public Optional<Money> minimumCharge(SupportedCurrency currency) {
		return currency == SupportedCurrency.USD ? Optional.of(Money.of("0.50", currency)) : Optional.empty();
	}

	@Override
	public Session createSession(Request r) {
		if (client == null) {
			throw new DomainRuleViolation("online_payment_unavailable", "Online payment is not available");
		}
		var locale = "ht".equalsIgnoreCase(r.locale()) ? "ht" : "fr";
		var base = config.returnBaseUrl().strip();
		var enc = java.net.URLEncoder.encode(r.reservationReference(), java.nio.charset.StandardCharsets.UTF_8);
		var expiresAt = clock.instant().plus(Duration.ofMinutes(config.checkoutExpirationMinutes()));
		var minor = r.amount().amount().movePointRight(r.amount().currency().fractionDigits()).longValueExact();
		var label = ("ht".equals(locale) ? "Rezèvasyon " : "Réservation ") + r.reservationReference();
		var params = SessionCreateParams.builder()
				.setMode(SessionCreateParams.Mode.PAYMENT)
				.setUiMode(SessionCreateParams.UiMode.HOSTED_PAGE)
				// Stripe's Adaptive Pricing would show/charge the customer in THEIR currency: an automatic conversion, which this business never does
				.setAdaptivePricing(SessionCreateParams.AdaptivePricing.builder().setEnabled(false).build())
				.setSuccessUrl(base + "/" + locale + "/payment/return?reference=" + enc + "&attempt=" + r.attemptId())
				.setCancelUrl(base + "/" + locale + "/payment/cancelled?reference=" + enc)
				.setClientReferenceId(r.attemptId().toString())
				.setExpiresAt(expiresAt.getEpochSecond())
				.putMetadata("attempt_id", r.attemptId().toString())
				.setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder().setDescription("Ana Dépôt — " + label).putMetadata("attempt_id", r.attemptId().toString()).build())
				.addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L).setPriceData(SessionCreateParams.LineItem.PriceData.builder()
						.setCurrency(r.amount().currency().name().toLowerCase(Locale.ROOT)).setUnitAmount(minor)
						.setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder().setName(label).build()).build()).build());
		if ("fr".equals(locale)) {
			params.setLocale(SessionCreateParams.Locale.FR);
		}
		if (r.customerEmail() != null && !r.customerEmail().isBlank()) {
			params.setCustomerEmail(r.customerEmail().strip());
		}
		try {
			var session = client.v1().checkout().sessions().create(params.build(), RequestOptions.builder().setIdempotencyKey("ana-attempt-" + r.attemptId()).build());
			if (session.getUrl() == null || session.getId() == null) {
				throw new DomainRuleViolation("payment_provider_error", "The payment provider did not return a checkout page");
			}
			return new Session(session.getId(), session.getUrl(), session.getExpiresAt() == null ? expiresAt : Instant.ofEpochSecond(session.getExpiresAt()));
		} catch (StripeException e) {
			log.warn("Stripe: checkout session was refused (status {}, code {})", e.getStatusCode(), e.getCode());
			if ("amount_too_small".equals(e.getCode())) {
				throw new DomainRuleViolation("amount_below_minimum", "The amount is below the minimum the payment provider accepts");
			}
			if ("currency_not_supported".equals(e.getCode())) {
				throw new DomainRuleViolation("currency_not_available_online", "Online payment is not available in this currency");
			}
			throw new DomainRuleViolation("payment_provider_error", "The payment provider could not open the payment page. Try again later.");
		}
	}
}
