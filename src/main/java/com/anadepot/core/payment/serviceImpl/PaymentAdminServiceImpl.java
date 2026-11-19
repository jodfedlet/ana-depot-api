package com.anadepot.core.payment.serviceImpl;

import com.anadepot.core.payment.model.LedgerEntry;
import com.anadepot.core.payment.model.Payment;
import com.anadepot.core.payment.model.PaymentQuery;
import com.anadepot.core.payment.model.PaymentView;
import com.anadepot.core.payment.repository.PaymentRepository;
import com.anadepot.core.payment.service.PaymentAdminService;
import com.anadepot.core.reservation.repository.ReservationRepository;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.util.List;
import java.util.UUID;

public class PaymentAdminServiceImpl implements PaymentAdminService {

	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final TransactionBoundary tx;

	public PaymentAdminServiceImpl(PaymentRepository payments, ReservationRepository reservations, TransactionBoundary tx) {
		this.payments = payments;
		this.reservations = reservations;
		this.tx = tx;
	}

	@Override
	public PageResult<PaymentView> list(PaymentQuery query, PageRequest page) {
		return tx.inTransaction(() -> {
			var result = payments.findPage(query, page);
			return new PageResult<>(views(result.items()), result.page(), result.size(), result.totalItems());
		});
	}

	@Override
	public PaymentView get(UUID id) {
		return tx.inTransaction(() -> views(List.of(payments.findPayment(id).orElseThrow(() -> new NotFoundException("Payment", id)))).getFirst());
	}

	@Override
	public List<PaymentView> forReservation(UUID reservationId) {
		return tx.inTransaction(() -> {
			requireReservation(reservationId);
			return views(payments.payments(reservationId));
		});
	}

	@Override
	public List<LedgerEntry> ledger(UUID reservationId) {
		return tx.inTransaction(() -> {
			requireReservation(reservationId);
			return payments.ledger(reservationId);
		});
	}

	private void requireReservation(UUID id) {
		if (reservations.findById(id).isEmpty()) {
			throw new NotFoundException("Reservation", id);
		}
	}

	private List<PaymentView> views(List<Payment> list) {
		var ids = list.stream().map(Payment::id).toList();
		var reversals = payments.reversalsOf(ids);
		var references = reservations.referencesOf(list.stream().map(Payment::reservationId).distinct().toList());
		return list.stream().map(p -> new PaymentView(p, references.get(p.reservationId()), reversals.get(p.id()))).toList();
	}

	@Override
	public List<com.anadepot.core.shared.money.Money> refundDue(com.anadepot.core.reservation.model.Reservation r) {
		var ended = switch (r.status()) {
			case REJECTED, CANCELLED, EXPIRED -> true;
			default -> false;
		};
		if (!ended) {
			return List.of();
		}
		var card = payments.payments(r.id()).stream().filter(p -> p.method() == com.anadepot.core.payment.model.PaymentMethod.STRIPE_CHECKOUT).toList();
		if (card.isEmpty()) {
			return List.of();
		}
		var reversed = payments.reversalsOf(card.stream().map(com.anadepot.core.payment.model.Payment::id).toList());
		var perCurrency = new java.util.EnumMap<com.anadepot.core.shared.money.SupportedCurrency, com.anadepot.core.shared.money.Money>(com.anadepot.core.shared.money.SupportedCurrency.class);
		for (var p : card) {
			if (!reversed.containsKey(p.id())) {
				perCurrency.merge(p.amount().currency(), p.amount(), com.anadepot.core.shared.money.Money::plus);
			}
		}
		return List.copyOf(perCurrency.values());
	}
}
