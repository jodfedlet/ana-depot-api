package com.anadepot.core.notification.model;

/**
 * The event catalog of customer e-mails. Each value is emitted by exactly one backend business operation (docs/notifications.md). Authentication e-mails (address verification,
 * password recovery) are deliberately absent: Supabase Auth owns them and this application never duplicates them.
 */
public enum NotificationType {
	RESERVATION_SUBMITTED("reservation-submitted"),
	RESERVATION_CONFIRMED("reservation-confirmed"),
	RESERVATION_REJECTED("reservation-rejected"),
	RESERVATION_READY_FOR_PICKUP("reservation-ready"),
	RESERVATION_COLLECTED("reservation-collected"),
	RESERVATION_CANCELLED("reservation-cancelled"),
	RESERVATION_EXPIRED("reservation-expired"),
	/** A payment was RECORDED in the ledger (cash at pickup, or a verified Stripe payment): never emitted for an unconfirmed payment. */
	PAYMENT_RECEIVED("payment-received"),
	/** A hosted checkout ended as FAILED according to a verified provider event. */
	PAYMENT_FAILED("payment-failed"),
	/** A hosted checkout session expired according to a verified provider event. */
	CHECKOUT_EXPIRED("checkout-expired");

	private final String template;

	NotificationType(String template) {
		this.template = template;
	}

	/** Base name of the HTML and plain-text templates (templates/email/{name}.html, templates/email/txt/{name}.txt). */
	public String template() {
		return template;
	}

	public boolean isPayment() {
		return this == PAYMENT_RECEIVED || this == PAYMENT_FAILED || this == CHECKOUT_EXPIRED;
	}
}
