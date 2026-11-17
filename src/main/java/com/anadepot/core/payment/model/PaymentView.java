package com.anadepot.core.payment.model;

/** A payment as staff see it: the immutable payment, the reservation it belongs to and, if it was reversed, the compensating ledger entry. */
public record PaymentView(Payment payment, String reservationReference, LedgerEntry reversal) {

	public boolean reversed() {
		return reversal != null;
	}
}
