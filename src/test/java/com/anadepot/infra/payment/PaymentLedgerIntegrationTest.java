package com.anadepot.infra.payment;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.payment.model.LedgerEntryType;
import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.RecordPaymentCommand;
import com.anadepot.core.reservation.model.PaymentStatus;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentLedgerIntegrationTest extends AbstractIntegrationTest {

	private static final Instant RECEIVED_AT = Instant.parse("2026-11-15T10:00:00Z");

	@Autowired PaymentRepository paymentRepository;
	@Autowired LedgerEntryJpaRepository ledgerJpa;
	@Autowired PaymentJpaRepository paymentJpa;
	@Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
	private TransactionTemplate tx;

	@org.junit.jupiter.api.BeforeEach
	void txTemplate() {
		tx = new TransactionTemplate(transactionManager);
	}
	@Autowired AuditEventJpaRepository audit;

	private com.anadepot.core.reservation.model.Reservation readyMixed() {
		var product = stockedMattress(50);
		return ready(submit(key(), line(product, 2, SupportedCurrency.HTG), line(product, 3, SupportedCurrency.USD))); // 50000 HTG + 540 USD
	}

	private com.anadepot.core.payment.model.Payment pay(UUID reservationId, Money amount, String key) {
		return payments.record(new RecordPaymentCommand(reservationId, amount, PaymentMethod.CASH_AT_PICKUP, RECEIVED_AT, staff, "REC-1", "note", key));
	}

	@Test
	void everyPaymentHasAnImmutableLedgerEntryInItsOwnCurrencyAndAmount() {
		var r = readyMixed();
		var htgPayment = pay(r.id(), htg("20000.00"), key());
		var usdPayment = pay(r.id(), usd("100.00"), key());

		var ledger = paymentRepository.ledger(r.id());
		assertThat(ledger).hasSize(2);
		assertThat(ledger).extracting(e -> e.type()).containsOnly(LedgerEntryType.PAYMENT_RECEIVED);
		assertThat(ledger).extracting(e -> e.amount()).containsExactlyInAnyOrder(htg("20000.00"), usd("100.00"));
		assertThat(htgPayment.amount().currency()).isEqualTo(SupportedCurrency.HTG);
		assertThat(usdPayment.amount().currency()).isEqualTo(SupportedCurrency.USD);
		assertThat(paymentRepository.payments(r.id())).hasSize(2);
		var recorded = paymentRepository.findPayment(htgPayment.id()).orElseThrow();
		assertThat(recorded.recordedBy()).isEqualTo(staff.userId());
		assertThat(recorded.reference()).isEqualTo("REC-1");
		assertThat(recorded.method()).isEqualTo(PaymentMethod.CASH_AT_PICKUP);
	}

	@Test
	void aReversalIsANewNegativeEntryAndNeverEditsHistory() {
		var r = readyMixed();
		var payment = pay(r.id(), htg("50000.00"), key());
		assertThat(reservationPaid(r.id(), SupportedCurrency.HTG)).isEqualTo(htg("50000.00"));
		assertThat(reservations.get(r.id()).balances().get(SupportedCurrency.HTG).settled()).isTrue();

		payments.reverse(payment.id(), "counted wrong", staff, key());

		var ledger = paymentRepository.ledger(r.id());
		assertThat(ledger).extracting(e -> e.type()).containsExactly(LedgerEntryType.PAYMENT_RECEIVED, LedgerEntryType.PAYMENT_REVERSAL);
		assertThat(ledger.get(0).amount()).isEqualTo(htg("50000.00"));           // the original is untouched
		assertThat(ledger.get(1).amount()).isEqualTo(htg("-50000.00"));
		assertThat(ledger.get(1).reversesEntryId()).isEqualTo(ledger.get(0).id());
		assertThat(ledger.get(1).reason()).isEqualTo("counted wrong");
		assertThat(reservationPaid(r.id(), SupportedCurrency.HTG)).isEqualTo(htg("0.00"));
		assertThat(reservations.get(r.id()).balances().get(SupportedCurrency.HTG).due()).isEqualTo(htg("50000.00"));
		assertThat(paymentRepository.findPayment(payment.id())).isPresent(); // the payment record itself is kept
		assertThat(payments.reconcile(r.id()).consistent()).isTrue();
		// reversing twice is refused, and a reversal needs a reason
		assertThatThrownBy(() -> payments.reverse(payment.id(), "again", staff, key())).extracting("code").isEqualTo("already_reversed");
		assertThatThrownBy(() -> payments.reverse(payment.id(), " ", staff, key())).extracting("code").isEqualTo("reason_required");
		// after a reversal the currency can be paid again
		pay(r.id(), htg("50000.00"), key());
		assertThat(reservations.get(r.id()).balances().get(SupportedCurrency.HTG).settled()).isTrue();
	}

	@Test
	void anAdjustmentCorrectsWhatWasPaidInOneCurrencyWithAMandatoryReason() {
		var r = readyMixed();
		pay(r.id(), usd("540.00"), key());

		var entry = payments.adjust(r.id(), usd("-40.00"), "short change returned", staff, key());

		assertThat(entry.type()).isEqualTo(LedgerEntryType.ADJUSTMENT);
		assertThat(entry.amount()).isEqualTo(usd("-40.00"));
		assertThat(reservationPaid(r.id(), SupportedCurrency.USD)).isEqualTo(usd("500.00"));
		assertThat(reservations.get(r.id()).balances().get(SupportedCurrency.USD).due()).isEqualTo(usd("40.00"));
		assertThat(payments.reconcile(r.id()).consistent()).isTrue();
		assertThatThrownBy(() -> payments.adjust(r.id(), usd("5.00"), " ", staff, key())).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("reason_required");
		assertThatThrownBy(() -> payments.adjust(r.id(), usd("-999.00"), "too much", staff, key())).extracting("code").isEqualTo("paid_negative");
		assertThatThrownBy(() -> payments.adjust(r.id(), htg("0.00"), "zero", staff, key())).extracting("code").isEqualTo("ledger_amount_zero");
		// HTG was never touched
		assertThat(reservationPaid(r.id(), SupportedCurrency.HTG)).isEqualTo(htg("0.00"));
	}

	@Test
	void reconciliationDetectsADriftBetweenTheCachedPaidAmountAndTheLedger() {
		var r = readyMixed();
		pay(r.id(), htg("10000.00"), key());
		assertThat(payments.reconcile(r.id()).consistent()).isTrue();
		// simulate corruption of the cache (e.g. a manual UPDATE): the authoritative ledger still says 10000
		var reservation = reservations.get(r.id());
		tx.executeWithoutResult(s -> {
			var loaded = reservationRepo().findByIdForUpdate(r.id()).orElseThrow();
			loaded.applyLedger(htg("1.00"));
			reservationRepo().save(loaded);
		});
		var report = payments.reconcile(r.id());
		assertThat(report.consistent()).isFalse();
		var htgLine = report.lines().stream().filter(l -> l.currency() == SupportedCurrency.HTG).findFirst().orElseThrow();
		assertThat(htgLine.cachedPaid()).isEqualTo(htg("10001.00"));
		assertThat(htgLine.ledgerPaid()).isEqualTo(htg("10000.00"));
		assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
	}

	@Autowired com.anadepot.core.reservation.repository.ReservationRepository reservationRepository;

	private com.anadepot.core.reservation.repository.ReservationRepository reservationRepo() {
		return reservationRepository;
	}

	@Test
	void ledgerEntriesAndPaymentsCannotBeUpdatedOrDeletedEvenDirectly() {
		var r = readyMixed();
		var payment = pay(r.id(), htg("1000.00"), key());
		var entry = paymentRepository.ledger(r.id()).getFirst();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			var e = ledgerJpa.findById(entry.id()).orElseThrow();
			e.reason = "rewritten history";
			ledgerJpa.saveAndFlush(e);
		})).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			ledgerJpa.deleteById(entry.id());
			ledgerJpa.flush();
		})).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			var p = paymentJpa.findById(payment.id()).orElseThrow();
			p.note = "edited";
			paymentJpa.saveAndFlush(p);
		})).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThat(paymentRepository.ledger(r.id())).hasSize(1);
	}

	@Test
	void paymentsAreAuditedAndNoLedgerEntryExistsForNonFinancialEvents() {
		var product = stockedMattress(10);
		var r = submit(key(), line(product, 1, SupportedCurrency.HTG));
		ready(r);
		inventory.adjust(product.id(), null, com.anadepot.core.inventory.model.MovementType.ADD, 3, com.anadepot.core.inventory.model.StockReason.RECEPTION, null, staff.userId(), key());
		// accepting, readying and adjusting stock are not financial events: no ledger lines
		assertThat(paymentRepository.ledger(r.id())).isEmpty();
		var payment = pay(r.id(), htg("25000.00"), key());
		payments.reverse(payment.id(), "test", staff, key());
		var trail = audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("reservation", r.id().toString());
		assertThat(trail).extracting(e -> e.getAction()).contains("PAYMENT_RECORDED", "PAYMENT_REVERSED");
		var reversed = trail.stream().filter(e -> e.getAction().equals("PAYMENT_REVERSED")).findFirst().orElseThrow();
		assertThat(reversed.getReason()).isEqualTo("test");
		assertThat(reversed.getNewValues()).contains("-25000.00 HTG");
		assertThat(paymentRepository.ledger(r.id())).hasSize(2);
	}

	@Test
	void aSameKeyPaymentRetryAppliesOnceAndADifferentPayloadWithTheKeyIsRefused() {
		var r = readyMixed();
		var key = key();
		var first = pay(r.id(), htg("10000.00"), key);
		var retry = pay(r.id(), htg("10000.00"), key);
		assertThat(retry.id()).isEqualTo(first.id());
		assertThat(paymentRepository.payments(r.id())).hasSize(1);
		assertThat(reservationPaid(r.id(), SupportedCurrency.HTG)).isEqualTo(htg("10000.00")); // not 20000
		assertThatThrownBy(() -> pay(r.id(), htg("999.00"), key)).extracting("code").isEqualTo("idempotency_key_reuse");
		assertThat(paymentRepository.payments(r.id())).hasSize(1);
		// no key = refused: a retried financial write must be identifiable
		assertThatThrownBy(() -> pay(r.id(), htg("1.00"), null)).extracting("code").isEqualTo("idempotency_key_required");
	}

	private Money reservationPaid(UUID reservationId, SupportedCurrency currency) {
		return reservations.get(reservationId).paid().get(currency);
	}

	// ---------------------------------------------------------------- database constraints on payments and the ledger

	@Test
	void aPaymentCanOnlyBeInACurrencyTheReservationOwes() {
		var product = stockedMattress(10);
		var htgOnly = ready(submit(key(), line(product, 1, SupportedCurrency.HTG)));
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "violates foreign key constraint", () -> {
			var p = new PaymentEntity();
			p.id = UUID.randomUUID();
			p.reservationId = htgOnly.id();
			p.currency = SupportedCurrency.USD;         // no USD balance on this reservation
			p.amount = new java.math.BigDecimal("5.00");
			p.paymentMethod = PaymentMethod.CASH_AT_PICKUP;
			p.receivedAt = Instant.now();
			p.recordedAt = Instant.now();
			paymentJpa.saveAndFlush(p);
		});
	}

	@Test
	void ledgerShapeRulesAreEnforcedByTheDatabase() {
		var r = readyMixed();
		var payment = pay(r.id(), htg("1000.00"), key());
		var received = paymentRepository.ledger(r.id()).getFirst();
		LedgerEntryEntity base = null;
		// a "received" entry cannot be negative
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "violates check constraint", () -> ledgerJpa.saveAndFlush(entry(r.id(), payment.id(), LedgerEntryType.PAYMENT_RECEIVED, SupportedCurrency.HTG, "-5.00", null, null)));
		// an adjustment needs a reason
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "violates check constraint", () -> ledgerJpa.saveAndFlush(entry(r.id(), null, LedgerEntryType.ADJUSTMENT, SupportedCurrency.HTG, "5.00", null, " ")));
		// a zero entry is meaningless
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "violates check constraint", () -> ledgerJpa.saveAndFlush(entry(r.id(), null, LedgerEntryType.ADJUSTMENT, SupportedCurrency.HTG, "0.00", null, "zero")));
		// a reversal is negative, references the entry it reverses, and is in the SAME currency
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "violates foreign key constraint", () -> ledgerJpa.saveAndFlush(entry(r.id(), payment.id(), LedgerEntryType.PAYMENT_REVERSAL, SupportedCurrency.USD, "-5.00", received.id(), "wrong currency")));
		// an entry can be reversed only once
		payments.reverse(payment.id(), "first", staff, key());
		com.anadepot.infra.DatabaseAssertions.assertRejected(transactionManager, "duplicate key", () -> ledgerJpa.saveAndFlush(entry(r.id(), payment.id(), LedgerEntryType.PAYMENT_REVERSAL, SupportedCurrency.HTG, "-1000.00", received.id(), "second reversal")));
		assertThat(base).isNull();
	}

	private static LedgerEntryEntity entry(UUID reservationId, UUID paymentId, LedgerEntryType type, SupportedCurrency currency, String amount, UUID reverses, String reason) {
		var e = new LedgerEntryEntity();
		e.id = UUID.randomUUID();
		e.reservationId = reservationId;
		e.paymentId = paymentId;
		e.entryType = type;
		e.currency = currency;
		e.amount = new java.math.BigDecimal(amount);
		e.reversesEntryId = reverses;
		e.reason = reason;
		e.createdAt = Instant.now();
		return e;
	}
}
