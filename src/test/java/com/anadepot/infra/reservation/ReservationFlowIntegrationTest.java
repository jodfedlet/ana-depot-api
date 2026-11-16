package com.anadepot.infra.reservation;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.PaymentStatus;
import com.anadepot.core.reservation.model.RejectReason;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.testsupport.TestData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The reservation lifecycle against a real database: shared stock, snapshots, per-currency payments, audit. */
class ReservationFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired InventoryRepository inventoryRepository;
	@Autowired ReservationRepository reservationRepository;
	@Autowired AuditEventJpaRepository audit;

	private void pay(UUID reservationId, Money amount) {
		payments.record(new RecordPaymentCommand(reservationId, amount, PaymentMethod.CASH_AT_PICKUP, Instant.now(), staff, null, null, key()));
	}

	// ---------------------------------------------------------------- shared inventory

	@Test
	void htgAndUsdReservationsConsumeTheSameStockPosition() {
		var product = stockedMattress(10);

		submit(key(), line(product, 3, SupportedCurrency.HTG));
		submit(key(), line(product, 4, SupportedCurrency.USD));

		var item = inventory.get(product.id(), null);
		assertThat(item.onHand()).isEqualTo(10);
		assertThat(item.reserved()).isEqualTo(7);       // 3 (HTG) + 4 (USD) on the SAME record
		assertThat(item.available()).isEqualTo(3);
		// 4 more is too many in EITHER currency
		assertThatThrownBy(() -> submit(key(), line(product, 4, SupportedCurrency.HTG))).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("insufficient_stock");
		assertThatThrownBy(() -> submit(key(), line(product, 4, SupportedCurrency.USD))).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("insufficient_stock");
		submit(key(), line(product, 3, SupportedCurrency.USD)); // exactly the remainder is fine
		assertThat(inventory.get(product.id(), null).available()).isZero();
	}

	@Test
	void aSingleReservationCanHoldTheSameProductInBothCurrenciesAgainstOneStockPosition() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 2, SupportedCurrency.HTG), line(product, 3, SupportedCurrency.USD));
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(5);
		assertThat(r.totals().get(SupportedCurrency.HTG)).isEqualTo(htg("50000.00"));
		assertThat(r.totals().get(SupportedCurrency.USD)).isEqualTo(usd("540.00"));
	}

	@Test
	void failedReservationLeavesStockUntouched() {
		var product = stockedMattress(2);
		assertThatThrownBy(() -> submit(key(), line(product, 3, SupportedCurrency.HTG))).isInstanceOf(DomainRuleViolation.class);
		var item = inventory.get(product.id(), null);
		assertThat(item.reserved()).isZero();
		assertThat(item.onHand()).isEqualTo(2);
	}

	// ---------------------------------------------------------------- mixed currencies & snapshots

	@Test
	void mixedCurrencyReservationHasSeparateTotalsAndPriceSnapshots() {
		var a = stockedMattress(20);
		var b = stockedMattress(20);
		var r = submit(key(), line(a, 5, SupportedCurrency.HTG), line(b, 2, SupportedCurrency.USD));

		assertThat(r.totals().currencies()).containsExactly(SupportedCurrency.HTG, SupportedCurrency.USD);
		assertThat(r.totals().get(SupportedCurrency.HTG)).isEqualTo(htg("115000.00")); // 5 x 23000 (wholesale tier)
		assertThat(r.totals().get(SupportedCurrency.USD)).isEqualTo(usd("360.00"));    // 2 x 180 (retail)
		var htgLine = r.lines().get(0);
		var usdLine = r.lines().get(1);
		assertThat(htgLine.pricingKind()).isEqualTo(PricingKind.WHOLESALE);
		assertThat(htgLine.tierMinQuantity()).isEqualTo(5);
		assertThat(htgLine.unitPrice()).isEqualTo(htg("23000.00"));
		assertThat(usdLine.pricingKind()).isEqualTo(PricingKind.RETAIL);
		assertThat(usdLine.unitPrice()).isEqualTo(usd("180.00"));
		assertThat(htgLine.productName()).isEqualTo("Matelas d'essai");
		assertThat(htgLine.unit()).isEqualTo(a.unit());

		var reloaded = reservations.get(r.id());
		assertThat(reloaded.totals()).isEqualTo(r.totals());
		assertThat(reloaded.pickupSnapshotJson()).contains("pickup snapshot");
		assertThat(reloaded.status()).isEqualTo(ReservationStatus.PENDING_REVIEW);
		assertThat(reloaded.reference()).matches("ANA-[A-Z2-9]{6}");
	}

	@Test
	void changingProductPricesLaterNeverChangesAnExistingReservation() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 5, SupportedCurrency.HTG));

		var changed = new com.anadepot.core.catalog.model.Product(product.id(), product.sku(), product.slug(), product.categoryId(), product.name(), product.description(),
				product.brand(), product.unit(), product.unitNote(), ProductStatus.INACTIVE,
				com.anadepot.core.catalog.model.ProductPricing.of(java.util.List.of(TestData.price(htg("99999.00"), null, 1), TestData.price(usd("999.00"), null, 1))),
				product.variants(), product.attributes(), product.images(), product.createdAt(), product.updatedAt());
		catalog.saveProduct(changed, staff);

		var reloaded = reservations.get(r.id());
		assertThat(reloaded.lines().get(0).unitPrice()).isEqualTo(htg("23000.00"));
		assertThat(reloaded.lines().get(0).lineTotal()).isEqualTo(htg("115000.00"));
		assertThat(reloaded.totals().get(SupportedCurrency.HTG)).isEqualTo(htg("115000.00"));
		// ...while new reservations see the product is no longer for sale
		assertThatThrownBy(() -> submit(key(), line(product, 1, SupportedCurrency.HTG))).extracting("code").isEqualTo("product_unavailable");
	}

	@Test
	void requestsThePriceCannotBeServedAreRejected() {
		var product = stockedMattress(10, 1, 3);
		assertThatThrownBy(() -> submit(key(), line(product, 2, SupportedCurrency.USD))).extracting("code").isEqualTo("below_minimum_order");
		assertThat(submit(key(), line(product, 3, SupportedCurrency.USD)).status()).isEqualTo(ReservationStatus.PENDING_REVIEW);
		// a currency the product does not have, and a product that does not exist
		var category = TestData.category().category();
		catalog.saveCategory(category);
		var htgOnly = catalog.saveProduct(TestData.product(category.id(), ProductStatus.ACTIVE, TestData.price(htg("100.00"), null, 1)), staff);
		inventory.register(htgOnly.id(), null, true, 5, null, null);
		assertThatThrownBy(() -> submit(key(), line(htgOnly, 1, SupportedCurrency.USD))).extracting("code").isEqualTo("price_not_available");
		assertThatThrownBy(() -> reservations.submit(new com.anadepot.core.reservation.service.SubmitReservationCommand(
				java.util.List.of(new com.anadepot.core.reservation.service.SubmitReservationCommand.Line(UUID.randomUUID(), null, 1, SupportedCurrency.HTG)),
				contact("0000 5555"), "fr", PICKUP, com.anadepot.core.shared.Actor.guest(), null))).isInstanceOf(NotFoundException.class);
	}

	@Test
	void duplicateLinesForTheSameProductAndCurrencyAreRejected() {
		var product = stockedMattress(10);
		assertThatThrownBy(() -> submit(key(), line(product, 1, SupportedCurrency.HTG), line(product, 2, SupportedCurrency.HTG))).extracting("code").isEqualTo("duplicate_line");
	}

	// ---------------------------------------------------------------- payments per currency

	@Test
	void eachPaymentSettlesOnlyItsOwnCurrencyAndCollectionNeedsAllOfThem() {
		var product = stockedMattress(20);
		var r = submit(key(), line(product, 2, SupportedCurrency.HTG), line(product, 5, SupportedCurrency.USD)); // 50000 HTG + 5 x 165 = 825 USD
		ready(r);

		pay(r.id(), htg("50000.00"));
		var afterHtg = reservations.get(r.id());
		assertThat(afterHtg.balances().get(SupportedCurrency.HTG).due()).isEqualTo(htg("0.00"));
		assertThat(afterHtg.balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("825.00"));
		assertThat(afterHtg.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
		assertThatThrownBy(() -> reservations.collect(r.id(), staff, null)).extracting("code").isEqualTo("payment_required");

		pay(r.id(), usd("400.00"));
		assertThat(reservations.get(r.id()).balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("425.00"));
		assertThatThrownBy(() -> reservations.collect(r.id(), staff, null)).extracting("code").isEqualTo("payment_required");

		pay(r.id(), usd("425.00"));
		assertThat(reservations.get(r.id()).paymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(payments.reconcile(r.id()).consistent()).isTrue();

		var collected = reservations.collect(r.id(), staff, null);
		assertThat(collected.status()).isEqualTo(ReservationStatus.COLLECTED);
	}

	@Test
	void collectingDeductsPhysicalStockOnceAndRecordsASaleMovement() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 3, SupportedCurrency.HTG), line(product, 2, SupportedCurrency.USD));
		ready(r);
		pay(r.id(), htg("75000.00"));
		pay(r.id(), usd("360.00"));
		assertThat(inventory.get(product.id(), null).onHand()).isEqualTo(10);

		reservations.collect(r.id(), staff, null);

		var item = inventory.get(product.id(), null);
		assertThat(item.onHand()).isEqualTo(5);
		assertThat(item.reserved()).isZero();
		assertThat(item.available()).isEqualTo(5);
		var movements = inventoryRepository.movements(item.id());
		assertThat(movements).extracting(m -> m.type()).containsExactly(MovementType.INITIAL, MovementType.SALE);
		var sale = movements.get(1);
		assertThat(sale.delta()).isEqualTo(-5);
		assertThat(sale.balanceAfter()).isEqualTo(5);
		assertThat(sale.reservationId()).isEqualTo(r.id());
		assertThat(sale.reason()).isEqualTo(StockReason.SALE);
		// a second collection is refused by the lifecycle, so stock cannot be deducted twice
		assertThatThrownBy(() -> reservations.collect(r.id(), staff, null)).extracting("code").isEqualTo("invalid_status");
		assertThat(inventory.get(product.id(), null).onHand()).isEqualTo(5);
	}

	@Test
	void paymentRulesCurrencyStatusAndAmount() {
		var product = stockedMattress(10);
		var htgOnly = submit(key(), line(product, 1, SupportedCurrency.HTG));
		// not ready yet
		assertThatThrownBy(() -> pay(htgOnly.id(), htg("1.00"))).extracting("code").isEqualTo("invalid_status");
		ready(htgOnly);
		// a currency the reservation does not owe
		assertThatThrownBy(() -> pay(htgOnly.id(), usd("5.00"))).extracting("code").isEqualTo("currency_invalid");
		pay(htgOnly.id(), htg("25000.00"));
		// already settled
		assertThatThrownBy(() -> pay(htgOnly.id(), htg("1.00"))).extracting("code").isEqualTo("already_paid");
	}

	@Test
	void overpaymentIsCurrentlyAcceptedAndTheDueAmountFloorsAtZero_anUnresolvedBusinessDecision() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 1, SupportedCurrency.USD)); // 180 USD
		ready(r);
		pay(r.id(), usd("200.00"));
		var after = reservations.get(r.id());
		assertThat(after.paymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(after.balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("0.00"));
		assertThat(after.paid().get(SupportedCurrency.USD)).isEqualTo(usd("200.00")); // the original amount is preserved
	}

	// ---------------------------------------------------------------- stock release & lifecycle

	@Test
	void rejectingCancellingAndExpiringReleaseTheReservedStock() {
		var product = stockedMattress(20);
		var toReject = submit(key(), line(product, 4, SupportedCurrency.HTG));
		var toCancel = submit(key(), line(product, 3, SupportedCurrency.USD));
		var toExpire = submit(key(), line(product, 2, SupportedCurrency.HTG));
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(9);

		reservations.reject(toReject.id(), staff, RejectReason.PRODUCT_UNAVAILABLE, null, null);
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(5);
		ready(toCancel);
		reservations.cancel(toCancel.id(), staff, "customer asked", null);
		assertThat(inventory.get(product.id(), null).reserved()).isEqualTo(2);
		ready(toExpire);
		reservations.expire(toExpire.id(), com.anadepot.core.shared.Actor.system(), null);
		var item = inventory.get(product.id(), null);
		assertThat(item.reserved()).isZero();
		assertThat(item.onHand()).isEqualTo(20);     // nothing physical moved
		assertThat(reservationRepository.openHeldQuantity(new com.anadepot.core.reservation.model.ItemKey(product.id(), null))).isZero();
		assertThatThrownBy(() -> reservations.reject(toReject.id(), staff, RejectReason.OTHER, "x", null)).extracting("code").isEqualTo("invalid_status");
	}

	@Test
	void reservedStockAlwaysMatchesTheOpenReservations() {
		var product = stockedMattress(30);
		var a = submit(key(), line(product, 5, SupportedCurrency.HTG));
		var b = submit(key(), line(product, 6, SupportedCurrency.USD));
		submit(key(), line(product, 7, SupportedCurrency.HTG));
		reservations.reject(a.id(), staff, RejectReason.CANNOT_FULFILL, null, null);
		ready(b);
		pay(b.id(), usd("990.00"));   // 6 x 165
		reservations.collect(b.id(), staff, null);
		var item = inventory.get(product.id(), null);
		assertThat(item.reserved()).isEqualTo(reservationRepository.openHeldQuantity(new com.anadepot.core.reservation.model.ItemKey(product.id(), null)));
		assertThat(item.reserved()).isEqualTo(7);
	}

	// ---------------------------------------------------------------- audit

	@Test
	void everySensitiveStepLeavesAnAuditEntryWithActorAndStatusChange() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 1, SupportedCurrency.HTG));
		ready(r);
		pay(r.id(), htg("25000.00"));
		reservations.collect(r.id(), staff, null);

		var events = audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", r.id().toString());
		assertThat(events).extracting(e -> e.getAction()).containsExactly("RESERVATION_SUBMITTED", "RESERVATION_ACCEPTED", "RESERVATION_READY", "PAYMENT_RECORDED", "RESERVATION_COLLECTED");
		var accepted = events.get(1);
		assertThat(accepted.getActorKind()).isEqualTo("STAFF");
		assertThat(accepted.getActorUserId()).isEqualTo(staff.userId());
		assertThat(accepted.getPreviousValues()).contains("PENDING_REVIEW");
		assertThat(accepted.getNewValues()).contains("CONFIRMED");
		assertThat(events.get(0).getActorKind()).isEqualTo("CUSTOMER");
	}

	@Test
	void stockAdjustmentsRequireAReasonAndAKeyAndAreAudited() {
		var product = stockedMattress(10);
		assertThatThrownBy(() -> inventory.adjust(product.id(), null, MovementType.ADD, 5, StockReason.RECEPTION, null, staff.userId(), null)).extracting("code").isEqualTo("idempotency_key_required");
		assertThatThrownBy(() -> inventory.adjust(product.id(), null, MovementType.ADD, 5, null, null, staff.userId(), key())).extracting("code").isEqualTo("reason_required");
		var movement = inventory.adjust(product.id(), null, MovementType.ADD, 5, StockReason.RECEPTION, "delivery note 42", staff.userId(), key());
		assertThat(movement.balanceAfter()).isEqualTo(15);
		assertThat(movement.recordedBy()).isEqualTo(staff.userId());
		assertThat(inventory.get(product.id(), null).onHand()).isEqualTo(15);
		var item = inventory.get(product.id(), null);
		var trail = audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("inventory_item", item.id().toString());
		assertThat(trail).extracting(e -> e.getAction()).containsExactly("INVENTORY_ADJUSTED");
		assertThat(trail.get(0).getReason()).isEqualTo("RECEPTION");
		assertThatThrownBy(() -> inventory.adjust(product.id(), null, MovementType.REDUCE, 99, StockReason.LOSS, null, staff.userId(), key())).extracting("code").isEqualTo("stock_negative");
	}
}
