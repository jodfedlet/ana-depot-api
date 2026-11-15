package com.anadepot.core.payment.model;

/** Only cash at pickup exists. MonCash / NatCash are "coming soon" and are NOT modelled (adding one = a migration + a constant). */
public enum PaymentMethod {
	CASH_AT_PICKUP
}
