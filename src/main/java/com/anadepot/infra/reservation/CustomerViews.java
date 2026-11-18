package com.anadepot.infra.reservation;

import com.anadepot.core.payment.service.PaymentAdminService;
import com.anadepot.core.reservation.model.Reservation;
import org.springframework.stereotype.Component;

/** Builds the customer's view of ONE reservation including its payment history (the list pages stay light: no per-row payment query). */
@Component
public class CustomerViews {

	private final PaymentAdminService payments;

	CustomerViews(PaymentAdminService payments) {
		this.payments = payments;
	}

	public CustomerReservationView detail(Reservation r) {
		var lines = payments.forReservation(r.id()).stream()
				.map(v -> new CustomerReservationView.PaymentLine(v.payment().amount().currency().name(), v.payment().amount().amount().toPlainString(), v.payment().method().name(),
						v.payment().receivedAt(), v.reversed()))
				.toList();
		return CustomerReservationView.of(r, lines);
	}
}
