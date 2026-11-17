package com.anadepot.infra.payment;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stripe Checkout (docs/payments-stripe.md). Disabled by default. {@code returnBaseUrl}: the web origin customers come back to (https; plain http only for localhost);
 * the return paths are fixed in code. {@code apiBase}: only to point a local test double at the SDK. {@code toString} never prints a secret.
 */
@ConfigurationProperties(prefix = "ana.stripe")
public record StripeProperties(@DefaultValue("false") boolean enabled, String secretKey, String webhookSecret, List<String> enabledCurrencies, String returnBaseUrl,
		@DefaultValue("30") int checkoutExpirationMinutes, String apiBase) {

	@Override
	public String toString() {
		return "StripeProperties[enabled=" + enabled + ", currencies=" + enabledCurrencies + ", secrets=hidden]";
	}
}
