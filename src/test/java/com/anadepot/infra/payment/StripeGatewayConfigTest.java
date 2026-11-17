package com.anadepot.infra.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure checks of the Stripe configuration rules (no Spring, no network). */
class StripeGatewayConfigTest {

	private static StripeProperties config(String key, String secret, List<String> currencies, String base, int minutes) {
		return new StripeProperties(true, key, secret, currencies, base, minutes, null);
	}

	@Test
	void returnBaseMustBeAnHttpsOriginOrLoopback() {
		assertThat(StripeCheckoutGateway.validReturnBase("https://shop.example.com")).isTrue();
		assertThat(StripeCheckoutGateway.validReturnBase("http://localhost:3100")).isTrue();
		assertThat(StripeCheckoutGateway.validReturnBase("http://127.0.0.1:3100")).isTrue();
		assertThat(StripeCheckoutGateway.validReturnBase("http://shop.example.com")).isFalse();        // plain http outside localhost
		assertThat(StripeCheckoutGateway.validReturnBase("https://shop.example.com/path")).isFalse();  // an origin only: paths are fixed in code
		assertThat(StripeCheckoutGateway.validReturnBase("https://user:pw@shop.example.com")).isFalse();
		assertThat(StripeCheckoutGateway.validReturnBase("https://shop.example.com?x=1")).isFalse();
		assertThat(StripeCheckoutGateway.validReturnBase("javascript:alert(1)")).isFalse();
		assertThat(StripeCheckoutGateway.validReturnBase("")).isFalse();
		assertThat(StripeCheckoutGateway.validReturnBase(null)).isFalse();
	}

	@Test
	void enablingStripeWithoutWhatItNeedsIsReportedAndNeverPrintsSecrets() {
		var empty = config(null, null, List.of(), null, 30);
		assertThat(StripeCheckoutGateway.problems(empty, StripeCheckoutGateway.parseCurrencies(empty.enabledCurrencies()))).hasSize(4);
		var good = config("sk_test_x", "whsec_x", List.of("USD"), "https://shop.example.com", 30);
		assertThat(StripeCheckoutGateway.problems(good, StripeCheckoutGateway.parseCurrencies(good.enabledCurrencies()))).isEmpty();
		assertThat(StripeCheckoutGateway.problems(config("sk_test_x", "whsec_x", List.of("USD"), "https://shop.example.com", 5), java.util.Set.of(SupportedCurrency.USD))).hasSize(1); // below Stripe's 30-minute minimum
		assertThat(good.toString()).doesNotContain("sk_test_x").doesNotContain("whsec_x");
	}

	@Test
	void currenciesAreParsedFromACommaListAndRejectUnknownCodes() {
		assertThat(StripeCheckoutGateway.parseCurrencies(List.of("USD, HTG"))).containsExactlyInAnyOrder(SupportedCurrency.USD, SupportedCurrency.HTG);
		assertThat(StripeCheckoutGateway.parseCurrencies(List.of())).isEmpty();
		assertThat(StripeCheckoutGateway.parseCurrencies(null)).isEmpty();
		org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> StripeCheckoutGateway.parseCurrencies(List.of("EUR")));
	}
}
