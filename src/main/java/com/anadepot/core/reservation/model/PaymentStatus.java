package com.anadepot.core.reservation.model;

/** Derived from the per-currency balances: PAID only when EVERY currency is settled. Never stored as a free-form flag. */
public enum PaymentStatus {
	UNPAID, PARTIAL, PAID
}
