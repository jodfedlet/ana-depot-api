package com.anadepot.core.reservation;

import static com.anadepot.testsupport.TestData.T0;
import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.model.NoteVisibility;
import com.anadepot.core.reservation.model.PaymentStatus;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.Reservation;
import com.anadepot.core.reservation.model.ReservationLine;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.CurrencyTotals;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

	private final Actor staff = Actor.staff(UUID.randomUUID());
	private final Instant now = T0;

	private ReservationLine line(UUID productId, UUID variantId, int qty, Money unitPrice, PricingKind kind, Integer tier) {
		return new ReservationLine(UUID.randomUUID(), productId, variantId, "Matelas", "SKU-1", null, SellingUnit.UNIT, qty, unitPrice, kind, tier, unitPrice.times(qty));
	}

	private Reservation mixed() {
		var product = UUID.randomUUID();
		var other = UUID.randomUUID();
		return Reservation.submit(UUID.randomUUID(), "ANA-ABC234", "fr", new Contact("Marie", "Exemple", "+509", "0000 1234", null), null,
				List.of(line(product, null, 2, htg("2500.00"), PricingKind.RETAIL, null),     // 5000 HTG
						line(other, null, 4, usd("10.00"), PricingKind.WHOLESALE, 4),       // 40 USD
						line(UUID.randomUUID(), null, 1, htg("1000.00"), PricingKind.SALE, null)), // 1000 HTG
				"{\"store\":\"snapshot\"}", now);
	}

	private Reservation readyMixed() {
		var r = mixed();
		r.accept(staff, now);
		r.markReady(staff, now, null);
		return r;
	}

	@Test
	void mixedCurrencyLinesProduceSeparateTotalsAndNeverACombinedOne() {
		var r = mixed();
		assertThat(r.totals().currencies()).containsExactly(SupportedCurrency.HTG, SupportedCurrency.USD);
		assertThat(r.totals().get(SupportedCurrency.HTG)).isEqualTo(htg("6000.00"));
		assertThat(r.totals().get(SupportedCurrency.USD)).isEqualTo(usd("40.00"));
		assertThat(r.status()).isEqualTo(ReservationStatus.PENDING_REVIEW);
		assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
		assertThat(r.history()).hasSize(1);
	}

	@Test
	void linesSnapshotCurrencyPriceKindTierAndTotal() {
		var l = mixed().lines().get(1);
		assertThat(l.unitPrice()).isEqualTo(usd("10.00"));
		assertThat(l.pricingKind()).isEqualTo(PricingKind.WHOLESALE);
		assertThat(l.tierMinQuantity()).isEqualTo(4);
		assertThat(l.lineTotal()).isEqualTo(usd("40.00"));
		assertThat(l.unit()).isEqualTo(SellingUnit.UNIT);
	}

	@Test
	void aLineWhoseTotalOrTierIsInconsistentIsRejected() {
		var p = UUID.randomUUID();
		assertThatThrownBy(() -> new ReservationLine(UUID.randomUUID(), p, null, "x", null, null, SellingUnit.UNIT, 2, htg("10"), PricingKind.RETAIL, null, htg("25")))
				.extracting("code").isEqualTo("line_total_mismatch");
		assertThatThrownBy(() -> new ReservationLine(UUID.randomUUID(), p, null, "x", null, null, SellingUnit.UNIT, 2, htg("10"), PricingKind.WHOLESALE, null, htg("20")))
				.extracting("code").isEqualTo("tier_mismatch");
		assertThatThrownBy(() -> new ReservationLine(UUID.randomUUID(), p, null, "x", null, null, SellingUnit.UNIT, 2, htg("10"), PricingKind.RETAIL, null, usd("20")))
				.extracting("code").isEqualTo("line_total_mismatch"); // a line total in another currency
	}

	@Test
	void totalsThatDoNotMatchTheLinesAreRejected() {
		var l = line(UUID.randomUUID(), null, 1, htg("10.00"), PricingKind.RETAIL, null);
		assertThatThrownBy(() -> new Reservation(UUID.randomUUID(), "ANA-ABC234", "fr", new Contact("A", "B", "+509", "123456", null), null, List.of(l),
				CurrencyTotals.empty().plus(htg("99.00")), CurrencyTotals.empty(), "{}", now, ReservationStatus.PENDING_REVIEW, null, null, List.of(), List.of()))
				.extracting("code").isEqualTo("totals_mismatch");
	}

	@Test
	void pickupInformationMustBeSnapshotted() {
		var l = line(UUID.randomUUID(), null, 1, htg("10.00"), PricingKind.RETAIL, null);
		assertThatThrownBy(() -> Reservation.submit(UUID.randomUUID(), "ANA-ABC234", "fr", new Contact("A", "B", "+509", "123456", null), null, List.of(l), " ", now))
				.extracting("code").isEqualTo("pickup_required");
	}

	@Test
	void balancesAreTrackedPerCurrency() {
		var r = readyMixed();
		var balances = r.balances();
		assertThat(balances.get(SupportedCurrency.HTG).due()).isEqualTo(htg("6000.00"));
		r.applyLedger(htg("6000.00"));
		assertThat(r.balances().get(SupportedCurrency.HTG).settled()).isTrue();
		assertThat(r.balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("40.00"));
		assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
		r.applyLedger(usd("20.00"));
		assertThat(r.balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("20.00"));
		assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
		r.applyLedger(usd("20.00"));
		assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PAID); // only when EVERY currency is settled
	}

	@Test
	void aLargeAmountInOneCurrencyNeverSettlesAnother() {
		var r = readyMixed();
		r.applyLedger(usd("1000000.00"));
		assertThat(r.balances().get(SupportedCurrency.HTG).due()).isEqualTo(htg("6000.00"));
		assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
	}

	@Test
	void aCurrencyTheReservationDoesNotOweIsRejected_andPaidCannotGoNegative() {
		var htgOnly = Reservation.submit(UUID.randomUUID(), "ANA-ABC234", "fr", new Contact("A", "B", "+509", "123456", null), null,
				List.of(line(UUID.randomUUID(), null, 1, htg("10.00"), PricingKind.RETAIL, null)), "{}", now);
		assertThatThrownBy(() -> htgOnly.applyLedger(usd("5.00"))).extracting("code").isEqualTo("currency_invalid");
		assertThatThrownBy(() -> htgOnly.applyLedger(htg("-1.00"))).extracting("code").isEqualTo("paid_negative");
	}

	@Test
	void collectionRequiresEveryCurrencyPaid() {
		var r = readyMixed();
		assertThatThrownBy(() -> r.collect(staff, now)).extracting("code").isEqualTo("payment_required");
		r.applyLedger(htg("6000.00"));
		assertThatThrownBy(() -> r.collect(staff, now)).extracting("code").isEqualTo("payment_required"); // USD still owed
		r.applyLedger(usd("40.00"));
		r.collect(staff, now);
		assertThat(r.status()).isEqualTo(ReservationStatus.COLLECTED);
	}

	@Test
	void lifecycleCannotSkipSteps() {
		var r = mixed();
		assertThatThrownBy(() -> r.markReady(staff, now, null)).extracting("code").isEqualTo("invalid_status");
		assertThatThrownBy(() -> r.collect(staff, now)).extracting("code").isEqualTo("invalid_status");
		r.accept(staff, now);
		assertThatThrownBy(() -> r.accept(staff, now)).extracting("code").isEqualTo("invalid_status");
		assertThatThrownBy(() -> r.reject(staff, now, RejectReason.CANNOT_FULFILL, null)).extracting("code").isEqualTo("invalid_status"); // only pending can be rejected
	}

	@Test
	void rejectionAndCancellationRequireReasons_andHistoryIsKept() {
		var rejected = mixed();
		assertThatThrownBy(() -> rejected.reject(staff, now, null, null)).extracting("code").isEqualTo("reason_required");
		assertThatThrownBy(() -> rejected.reject(staff, now, RejectReason.OTHER, " ")).extracting("code").isEqualTo("explanation_required");
		rejected.reject(staff, now, RejectReason.PRODUCT_UNAVAILABLE, null);
		assertThat(rejected.status()).isEqualTo(ReservationStatus.REJECTED);

		var cancelled = readyMixed();
		assertThatThrownBy(() -> cancelled.cancel(staff, now, " ")).extracting("code").isEqualTo("reason_required");
		cancelled.cancel(staff, now, "Customer called");
		assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(cancelled.history()).extracting(h -> h.to()).containsExactly(ReservationStatus.PENDING_REVIEW, ReservationStatus.CONFIRMED, ReservationStatus.READY_FOR_PICKUP, ReservationStatus.CANCELLED);
		assertThat(cancelled.history().getLast().reasonNote()).isEqualTo("Customer called");
		assertThat(cancelled.history().getLast().actor()).isEqualTo(staff);
		assertThatThrownBy(() -> cancelled.cancel(staff, now, "again")).extracting("code").isEqualTo("invalid_status");
	}

	@Test
	void noExpiryIsInventedWhenNoHoldPeriodIsConfigured() {
		var r = readyMixed();
		assertThat(r.expiresAt()).isNull();
		var withHold = mixed();
		withHold.accept(staff, now);
		withHold.markReady(staff, now, now.plusSeconds(86_400));
		assertThat(withHold.expiresAt()).isEqualTo(now.plusSeconds(86_400));
	}

	@Test
	void expiryOnlyFromConfirmedOrReady() {
		assertThatThrownBy(() -> mixed().expire(Actor.system(), now)).extracting("code").isEqualTo("invalid_status");
		var r = readyMixed();
		r.expire(Actor.system(), now);
		assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED);
	}

	@Test
	void internalAndCustomerVisibleNotesAreSeparate() {
		var r = mixed();
		r.addNote(NoteVisibility.INTERNAL, "call supplier", staff, now);
		r.addNote(NoteVisibility.CUSTOMER_VISIBLE, "ready tomorrow", staff, now);
		assertThat(r.notes()).extracting(n -> n.visibility()).containsExactly(NoteVisibility.INTERNAL, NoteVisibility.CUSTOMER_VISIBLE);
		assertThatThrownBy(() -> r.addNote(NoteVisibility.INTERNAL, " ", staff, now)).extracting("code").isEqualTo("note_empty");
	}

	@Test
	void stockHeldIsSharedAcrossCurrenciesOfTheSameProduct() {
		var product = UUID.randomUUID();
		var r = Reservation.submit(UUID.randomUUID(), "ANA-ABC234", "fr", new Contact("A", "B", "+509", "123456", null), null,
				List.of(line(product, null, 2, htg("2500.00"), PricingKind.RETAIL, null), line(product, null, 3, usd("18.00"), PricingKind.RETAIL, null)), "{}", now);
		assertThat(r.heldQuantities()).hasSize(1); // ONE stock position
		assertThat(r.heldQuantities().values()).containsExactly(5);
	}

	@Test
	void contactIsValidated() {
		assertThatThrownBy(() -> new Contact(" ", "B", "+509", "123456", null)).isInstanceOf(DomainRuleViolation.class);
		assertThatThrownBy(() -> new Contact("A", "B", "+509", "12", null)).isInstanceOf(DomainRuleViolation.class);
		assertThatThrownBy(() -> new Contact("A", "B", "+509", "123456", "not-an-email")).isInstanceOf(DomainRuleViolation.class);
		assertThat(new Contact("A", "B", "+509", "123456", "").email()).isNull();
	}
}
