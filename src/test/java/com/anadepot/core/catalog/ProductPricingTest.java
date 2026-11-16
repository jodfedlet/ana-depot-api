package com.anadepot.core.catalog;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.CurrencyMismatchException;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductPricingTest {

	// ---- one product, independent prices per currency ----

	@Test
	void aProductCanHaveHtgOnlyUsdOnlyOrBoth() {
		var htgOnly = ProductPricing.of(List.of(price(htg("25000"), null, 1)));
		var usdOnly = ProductPricing.of(List.of(price(usd("180"), null, 1)));
		var both = ProductPricing.of(List.of(price(htg("25000"), null, 1), price(usd("180"), null, 1)));
		assertThat(htgOnly.price(SupportedCurrency.USD)).isEmpty();
		assertThat(usdOnly.price(SupportedCurrency.HTG)).isEmpty();
		assertThat(both.all()).hasSize(2);
	}

	@Test
	void duplicatePricingForTheSameCurrencyIsRejected() {
		assertThatThrownBy(() -> ProductPricing.of(List.of(price(htg("100"), null, 1), price(htg("120"), null, 1))))
				.isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("duplicate_currency_price");
	}

	@Test
	void pricesAndWholesaleTiersAreIndependentPerCurrency() {
		var pricing = ProductPricing.of(List.of(
				price(htg("25000"), null, 1, tier(5, htg("23000"))),
				price(usd("180"), usd("170"), 3, tier(5, usd("165")), tier(20, usd("150")))));
		var h = pricing.requireActive(SupportedCurrency.HTG);
		var u = pricing.requireActive(SupportedCurrency.USD);
		assertThat(h.quote(null, 5).unitPrice()).isEqualTo(htg("23000"));
		assertThat(u.quote(null, 5).unitPrice()).isEqualTo(usd("165"));
		assertThat(h.quote(null, 20).unitPrice()).isEqualTo(htg("23000")); // HTG has no 20+ tier
		assertThat(u.quote(null, 20).unitPrice()).isEqualTo(usd("150"));
		assertThat(h.minOrderQuantity()).isEqualTo(1);
		assertThat(u.minOrderQuantity()).isEqualTo(3);
	}

	@Test
	void aMissingCurrencyIsNeverGeneratedOrConverted() {
		var pricing = ProductPricing.of(List.of(price(htg("25000"), null, 1)));
		assertThatThrownBy(() -> pricing.requireActive(SupportedCurrency.USD)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("price_not_available");
	}

	@Test
	void anInactivePriceConfigurationCannotBeSold() {
		var inactive = new ProductPrice(SupportedCurrency.USD, usd("10"), null, 1, false, List.of(), false);
		assertThatThrownBy(() -> ProductPricing.of(List.of(inactive)).requireActive(SupportedCurrency.USD)).isInstanceOf(DomainRuleViolation.class);
		assertThat(ProductPricing.of(List.of(inactive)).hasActivePrice()).isFalse();
	}

	@Test
	void everyAmountOfAPriceMustBeInThePriceCurrency() {
		assertThatThrownBy(() -> new ProductPrice(SupportedCurrency.HTG, htg("100"), usd("50"), 1, false, List.of(), true)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> new ProductPrice(SupportedCurrency.HTG, htg("100"), null, 1, true, List.of(tier(5, usd("90"))), true)).isInstanceOf(CurrencyMismatchException.class);
	}

	// ---- validation (same rules as the storefront's validatePricing) ----

	@Test
	void salePriceMustBePositiveAndBelowRetail() {
		assertThatThrownBy(() -> price(htg("100"), htg("100"), 1)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("sale_price_invalid");
		assertThatThrownBy(() -> price(htg("100"), htg("150"), 1)).isInstanceOf(DomainRuleViolation.class);
		assertThatThrownBy(() -> price(usd("100"), usd("0"), 1)).isInstanceOf(DomainRuleViolation.class);
		assertThat(price(usd("100"), usd("80"), 1).sale()).isEqualTo(usd("80"));
	}

	@Test
	void retailMustBePositiveAndMinimumOrderAtLeastOne() {
		assertThatThrownBy(() -> price(htg("0"), null, 1)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("retail_price_invalid");
		assertThatThrownBy(() -> price(htg("10"), null, 0)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("min_order_invalid");
	}

	@Test
	void wholesaleTiersNeedValidQuantitiesAndFallingPricesBelowTheBasePrice() {
		assertThatThrownBy(() -> price(htg("100"), null, 1, tier(1, htg("90")))).extracting("code").isEqualTo("tier_quantity_invalid");
		assertThatThrownBy(() -> price(htg("100"), null, 1, tier(5, htg("100")))).extracting("code").isEqualTo("tier_above_base");
		assertThatThrownBy(() -> price(htg("100"), htg("90"), 1, tier(5, htg("95")))).extracting("code").isEqualTo("tier_above_base"); // base is the SALE price
		assertThatThrownBy(() -> price(htg("100"), null, 1, tier(5, htg("90")), tier(5, htg("80")))).extracting("code").isEqualTo("tier_quantity_order");
		assertThatThrownBy(() -> price(htg("100"), null, 1, tier(5, htg("90")), tier(10, htg("95")))).extracting("code").isEqualTo("tier_price_order");
		assertThatThrownBy(() -> new ProductPrice(SupportedCurrency.HTG, htg("100"), null, 1, true, List.of(), true)).extracting("code").isEqualTo("tiers_empty");
		assertThatThrownBy(() -> new ProductPrice(SupportedCurrency.HTG, htg("100"), null, 1, false, List.of(tier(5, htg("90"))), true)).extracting("code").isEqualTo("wholesale_disabled_with_tiers");
	}

	// ---- price selection (same semantics as the storefront's unitPriceFor) ----

	@Test
	void theTierMatchingTheQuantityApplies() {
		var p = price(htg("1000"), null, 1, tier(10, htg("900")), tier(50, htg("800")));
		assertThat(p.quote(null, 9)).extracting("unitPrice", "kind", "tierMinQuantity").containsExactly(htg("1000"), PricingKind.RETAIL, null);
		assertThat(p.quote(null, 10)).extracting("unitPrice", "kind", "tierMinQuantity").containsExactly(htg("900"), PricingKind.WHOLESALE, 10);
		assertThat(p.quote(null, 49).unitPrice()).isEqualTo(htg("900"));
		assertThat(p.quote(null, 50)).extracting("unitPrice", "tierMinQuantity").containsExactly(htg("800"), 50);
		assertThat(p.quote(null, 50).lineTotal(50)).isEqualTo(htg("40000"));
	}

	@Test
	void aSalePriceIsReportedAsSaleUntilAWholesaleTierApplies() {
		var p = price(usd("100"), usd("90"), 1, tier(10, usd("80")));
		assertThat(p.quote(null, 1)).extracting("unitPrice", "kind").containsExactly(usd("90"), PricingKind.SALE);
		assertThat(p.quote(null, 10)).extracting("unitPrice", "kind").containsExactly(usd("80"), PricingKind.WHOLESALE);
	}

	@Test
	void aVariantWithItsOwnPriceScalesWholesaleTiersProportionally_andIgnoresTheProductSale() {
		// product: retail 25000, tier 5+ at 23000 (8% off). Variant retail 30000 -> tier 27600 (same 8% off).
		var p = price(htg("25000"), htg("24000"), 1, tier(5, htg("23000")));
		var v = p.quote(htg("30000"), 5);
		assertThat(v.unitPrice()).isEqualTo(htg("27600.00"));
		assertThat(v.kind()).isEqualTo(PricingKind.WHOLESALE);
		var below = p.quote(htg("30000"), 4);
		assertThat(below.unitPrice()).isEqualTo(htg("30000")); // the product's SALE price does not apply to a variant with its own price
		assertThat(below.kind()).isEqualTo(PricingKind.RETAIL);
		// proportional scaling rounds HALF_UP once: 23000 * 12345.67 / 25000 = 11358.0164 -> 11358.02
		assertThat(p.quote(htg("12345.67"), 5).unitPrice()).isEqualTo(htg("11358.02"));
	}

	@Test
	void aTierPriceNeverExceedsTheBasePriceOfAVariant() {
		var p = price(htg("1000"), null, 1, tier(5, htg("990")));
		assertThat(p.quote(htg("100"), 5).unitPrice()).isEqualTo(htg("99.00")); // scaled 990*100/1000, still below base
	}

	@Test
	void aVariantPriceInAnotherCurrencyIsRejected() {
		assertThatThrownBy(() -> price(htg("100"), null, 1).quote(usd("100"), 1)).isInstanceOf(CurrencyMismatchException.class);
	}
}
