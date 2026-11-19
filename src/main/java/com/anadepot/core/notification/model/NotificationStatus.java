package com.anadepot.core.notification.model;

/**
 * Delivery state. {@code SENT} means a mail server ACCEPTED the message (never "the customer read it", and never claimed when delivery was not attempted).
 * {@code CAPTURED}: local development wrote the message to a file; nothing was sent. The three SKIPPED states are final and are never retried.
 */
public enum NotificationStatus {
	PENDING, PROCESSING, RETRY, SENT, DEAD, CAPTURED, SKIPPED_NO_RECIPIENT, SKIPPED_INVALID_RECIPIENT, SKIPPED_THROTTLED;

	public boolean isFinal() {
		return this == SENT || this == DEAD || this == CAPTURED || name().startsWith("SKIPPED_");
	}
}
