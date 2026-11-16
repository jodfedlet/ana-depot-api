package com.anadepot.infra.reservation;

import static com.anadepot.infra.DatabaseAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class ReservationConstraintsIntegrationTest extends AbstractIntegrationTest {

	@Autowired PlatformTransactionManager tm;
	@Autowired ReservationJpaRepository jpa;

	private UUID htgReservation() {
		return submit(key(), line(stockedMattress(10), 2, SupportedCurrency.HTG)).id();
	}

	@Test
	void aLineCanOnlyUseACurrencyThatHasATotalOnItsReservation() {
		var id = htgReservation();
		assertRejected(tm, "violates foreign key constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			var l = r.lines.getFirst();
			l.currency = SupportedCurrency.USD;           // the reservation has only an HTG total
			jpa.saveAndFlush(r);
		});
	}

	@Test
	void aLineTotalMustEqualUnitPriceTimesQuantity_andTierMustMatchThePriceKind() {
		var id = htgReservation();
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.lines.getFirst().lineTotal = new BigDecimal("1.00");
			jpa.saveAndFlush(r);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.lines.getFirst().pricingKind = PricingKind.WHOLESALE;   // wholesale without a recorded tier
			jpa.saveAndFlush(r);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.lines.getFirst().quantity = 0;
			jpa.saveAndFlush(r);
		});
	}

	@Test
	void aReservationHasOneTotalPerCurrency_paidCannotBeNegative_referencesAreWellFormed() {
		var id = htgReservation();
		assertRejected(tm, "duplicate key", () -> {
			var r = jpa.findById(id).orElseThrow();
			var again = new ReservationTotalEntity();
			again.id = UUID.randomUUID();
			again.reservation = r;
			again.currency = SupportedCurrency.HTG;
			again.totalAmount = new BigDecimal("1.00");
			again.paidAmount = BigDecimal.ZERO;
			r.totals.add(again);
			jpa.saveAndFlush(r);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.totals.getFirst().paidAmount = new BigDecimal("-1.00");
			jpa.saveAndFlush(r);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.reference = "BAD-REF";
			jpa.saveAndFlush(r);
		});
	}

	@Test
	void aReadyReservationMustHaveAReadyTimestamp_andTheStoredSnapshotsAreDurable() {
		var id = htgReservation();
		assertRejected(tm, "violates check constraint", () -> {
			var r = jpa.findById(id).orElseThrow();
			r.status = ReservationStatus.READY_FOR_PICKUP;   // readyAt is null
			jpa.saveAndFlush(r);
		});
		new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(t -> {
			var stored = jpa.findById(id).orElseThrow();
			assertThat(stored.status).isEqualTo(ReservationStatus.PENDING_REVIEW);
			assertThat(stored.pickupSnapshot).contains("pickup snapshot");
			assertThat(stored.history).hasSize(1);
		});
	}
}
