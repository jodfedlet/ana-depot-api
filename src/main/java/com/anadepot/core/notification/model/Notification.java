package com.anadepot.core.notification.model;

import java.time.Instant;
import java.util.UUID;

/** One customer message and its delivery state. Holds references and a masked recipient hint, never the body or the address. */
public record Notification(UUID id, UUID eventId, String dedupeKey, NotificationType type, UUID reservationId, UUID paymentId, UUID attemptId, String locale,
		String recipientHint, String recipientHash, NotificationStatus status, int attempts, Instant nextAttemptAt, String lastErrorCode, Instant lastErrorAt, Instant sentAt,
		Instant createdAt, Instant updatedAt, Instant revivedAt) {

	/** When this message started waiting: its creation, or the last time staff put it back in the queue. */
	public Instant waitingSince() {
		return revivedAt != null && revivedAt.isAfter(createdAt) ? revivedAt : createdAt;
	}

	public Notification revived(Instant now) {
		return new Notification(id, eventId, dedupeKey, type, reservationId, paymentId, attemptId, locale, recipientHint, recipientHash, NotificationStatus.PENDING, 0, now, null, null, null, createdAt, now, now);
	}

	public Notification with(NotificationStatus status, int attempts, Instant nextAttemptAt, String lastErrorCode, Instant lastErrorAt, Instant sentAt, Instant now) {
		return new Notification(id, eventId, dedupeKey, type, reservationId, paymentId, attemptId, locale, recipientHint, recipientHash, status, attempts, nextAttemptAt, lastErrorCode,
				lastErrorAt, sentAt, createdAt, now, revivedAt);
	}
}
