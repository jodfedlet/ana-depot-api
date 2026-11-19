package com.anadepot.core.notification.model;

import java.util.UUID;

/**
 * What a business operation asks for, inside its own transaction. {@code dedupeKey} makes the request idempotent: asking twice for the same business fact (a duplicate webhook,
 * a repeated event) creates one message. {@code recipientEmail} is only used to decide whether a message can be sent and to build the masked hint and the throttle hash; it is not stored.
 */
public record NotificationRequest(NotificationType type, UUID reservationId, UUID paymentId, UUID attemptId, String locale, String recipientEmail, String dedupeKey) {

	public static NotificationRequest reservation(NotificationType type, UUID reservationId, String locale, String recipientEmail) {
		return new NotificationRequest(type, reservationId, null, null, locale, recipientEmail, type.name() + ":" + reservationId);
	}

	public static NotificationRequest payment(UUID reservationId, UUID paymentId, String locale, String recipientEmail) {
		return new NotificationRequest(NotificationType.PAYMENT_RECEIVED, reservationId, paymentId, null, locale, recipientEmail, "PAYMENT_RECEIVED:" + paymentId);
	}

	public static NotificationRequest attempt(NotificationType type, UUID reservationId, UUID attemptId, String locale, String recipientEmail) {
		return new NotificationRequest(type, reservationId, null, attemptId, locale, recipientEmail, type.name() + ":" + attemptId);
	}

	@Override
	public String toString() {
		return "NotificationRequest[" + type + ", reservation=" + reservationId + ", recipient=hidden]";
	}
}
