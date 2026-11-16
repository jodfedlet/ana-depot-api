package com.anadepot.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.shared.money.CurrencyMismatchException;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void createsHtgAndUsdAmountsWithTheirCurrencyAndTwoDecimals() {
		var htg = Money.of("25000", SupportedCurrency.HTG);
		var usd = Money.of("180.5", SupportedCurrency.USD);
		assertThat(htg.toString()).isEqualTo("25000.00 HTG");
		assertThat(usd.toString()).isEqualTo("180.50 USD");
		assertThat(htg.currency()).isEqualTo(SupportedCurrency.HTG);
		assertThat(usd.currency()).isEqualTo(SupportedCurrency.USD);
	}

	@Test
	void sameCurrencyArithmeticWorksWithExactDecimals() {
		assertThat(Money.of("0.10", SupportedCurrency.USD).plus(Money.of("0.20", SupportedCurrency.USD))).isEqualTo(Money.of("0.30", SupportedCurrency.USD)); // no 0.30000000000000004
		assertThat(Money.of("100", SupportedCurrency.HTG).minus(Money.of("40.25", SupportedCurrency.HTG))).isEqualTo(Money.of("59.75", SupportedCurrency.HTG));
		assertThat(Money.of("19.99", SupportedCurrency.USD).times(3)).isEqualTo(Money.of("59.97", SupportedCurrency.USD));
		assertThat(Money.of("5", SupportedCurrency.HTG).minusFloorZero(Money.of("9", SupportedCurrency.HTG))).isEqualTo(Money.zero(SupportedCurrency.HTG));
	}

	@Test
	void crossCurrencyArithmeticAndComparisonAreRejected() {
		var htg = Money.of("100", SupportedCurrency.HTG);
		var usd = Money.of("100", SupportedCurrency.USD);
		assertThatThrownBy(() -> htg.plus(usd)).isInstanceOf(CurrencyMismatchException.class).extracting("code").isEqualTo("currency_mismatch");
		assertThatThrownBy(() -> htg.minus(usd)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> htg.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> htg.min(usd)).isInstanceOf(CurrencyMismatchException.class);
		assertThat(htg).isNotEqualTo(usd); // same number, different currency: never equal
	}

	@Test
	void extraDecimalsAreRejectedNotSilentlyRounded_butExplicitRoundingIsHalfUp() {
		assertThatThrownBy(() -> Money.of("10.001", SupportedCurrency.USD)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("invalid_amount");
		assertThat(Money.of("10.500", SupportedCurrency.USD).amount()).isEqualByComparingTo("10.5"); // trailing zeros are fine
		assertThat(Money.roundedFrom(new BigDecimal("2.005"), SupportedCurrency.USD)).isEqualTo(Money.of("2.01", SupportedCurrency.USD));
		assertThat(Money.roundedFrom(new BigDecimal("2.004"), SupportedCurrency.USD)).isEqualTo(Money.of("2.00", SupportedCurrency.USD));
		assertThatThrownBy(() -> Money.of("abc", SupportedCurrency.HTG)).isInstanceOf(DomainRuleViolation.class);
	}

	@Test
	void onlyHtgAndUsdAreSupported() {
		assertThat(SupportedCurrency.values()).containsExactly(SupportedCurrency.HTG, SupportedCurrency.USD);
		assertThat(SupportedCurrency.parse("usd")).isEqualTo(SupportedCurrency.USD);
		assertThatThrownBy(() -> SupportedCurrency.parse("EUR")).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("unsupported_currency");
	}

	@Test
	void currencyTotalsKeepOneAmountPerCurrencyAndNeverMerge() {
		var totals = CurrencyTotals.sum(java.util.List.of(Money.of("5000", SupportedCurrency.HTG), Money.of("20", SupportedCurrency.USD),
				Money.of("1000", SupportedCurrency.HTG), Money.of("20", SupportedCurrency.USD)));
		assertThat(totals.currencies()).containsExactly(SupportedCurrency.HTG, SupportedCurrency.USD);
		assertThat(totals.get(SupportedCurrency.HTG)).isEqualTo(Money.of("6000", SupportedCurrency.HTG));
		assertThat(totals.get(SupportedCurrency.USD)).isEqualTo(Money.of("40", SupportedCurrency.USD));
		assertThat(CurrencyTotals.empty().get(SupportedCurrency.USD)).isEqualTo(Money.zero(SupportedCurrency.USD));
		assertThat(CurrencyTotals.empty().has(SupportedCurrency.HTG)).isFalse();
	}
}
