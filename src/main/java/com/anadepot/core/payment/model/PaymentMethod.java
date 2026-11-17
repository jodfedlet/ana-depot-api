package com.anadepot.core.payment.model;

/** Cash at pickup (recorded by staff) and Stripe Checkout (posted ONLY from a verified provider event). MonCash / NatCash are "coming soon" and are NOT modelled. */
public enum PaymentMethod {
	CASH_AT_PICKUP, STRIPE_CHECKOUT
}
