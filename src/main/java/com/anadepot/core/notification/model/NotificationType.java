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

	/**
	 * Messages the customer must not miss: a decision about their reservation, that it is ready, or that money was received. They keep a longer age limit, bypass the per-address
	 * throttle (only staff or a verified provider event can cause them, so a stranger cannot use them to flood an address) and, when they expire, raise a warning to staff.
	 */
	public boolean important() {
		return switch (this) {
			case RESERVATION_CONFIRMED, RESERVATION_REJECTED, RESERVATION_READY_FOR_PICKUP, RESERVATION_CANCELLED, RESERVATION_EXPIRED, PAYMENT_RECEIVED -> true;
			default -> false;
		};
	}

	/** Messages that a PUBLIC action (a reservation form, a checkout) can cause: the only ones the per-address throttle may hold back. */
	public boolean customerTriggered() {
		return this == RESERVATION_SUBMITTED || this == PAYMENT_FAILED || this == CHECKOUT_EXPIRED;
	}

	public boolean isPayment() {
		return this == PAYMENT_RECEIVED || this == PAYMENT_FAILED || this == CHECKOUT_EXPIRED;
	}
}
