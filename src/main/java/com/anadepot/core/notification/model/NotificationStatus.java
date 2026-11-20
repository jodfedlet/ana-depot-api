package com.anadepot.core.notification.model;

/**
 * Delivery state. {@code SENT} means a mail server ACCEPTED the message (never "the customer read it", and never claimed when delivery was not attempted).
 * {@code CAPTURED}: local development wrote the message to a file; nothing was sent. The three SKIPPED states are final and are never retried.
 * {@code SUPERSEDED}: the message would no longer be true (the reservation moved on), so it was deliberately not sent: not a failure, nothing to fix.
 * {@code EXPIRED}: it waited longer than its limit and was not sent; for an important message the customer may not have been told, so staff are warned and can resend it.
 */
public enum NotificationStatus {
	PENDING, PROCESSING, RETRY, SENT, DEAD, CAPTURED, SKIPPED_NO_RECIPIENT, SKIPPED_INVALID_RECIPIENT, SKIPPED_THROTTLED, SUPERSEDED, EXPIRED;

	public boolean isFinal() {
		return this == SENT || this == DEAD || this == CAPTURED || this == SUPERSEDED || this == EXPIRED || name().startsWith("SKIPPED_");
	}
}
