package com.anadepot.core.notification.serviceImpl;

import com.anadepot.core.notification.model.EmailAddress;
import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationRequest;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.notification.service.NotificationRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

public class NotificationRecorderImpl implements NotificationRecorder {

	private final NotificationRepository notifications;
	private final Clock clock;
	private final int perRecipientPerHour;

	public NotificationRecorderImpl(NotificationRepository notifications, Clock clock, int perRecipientPerHour) {
		this.notifications = notifications;
		this.clock = clock;
		this.perRecipientPerHour = perRecipientPerHour;
	}

	@Override
	public void record(NotificationRequest r) {
		var now = clock.instant();
		var email = r.recipientEmail();
		NotificationStatus status = NotificationStatus.PENDING;
		String hint = null;
		String hash = null;
		if (email == null || email.isBlank()) {
			status = NotificationStatus.SKIPPED_NO_RECIPIENT;       // a guest who gave no address: visible to staff, never retried
		} else if (!EmailAddress.isValid(email)) {
			status = NotificationStatus.SKIPPED_INVALID_RECIPIENT;
		} else {
			hint = EmailAddress.mask(email);
			hash = EmailAddress.hash(email);
			// A reservation form can name any address: one address can only be written to so often, so a stranger cannot be flooded through our shop.
			// Only what a PUBLIC action can cause is held back: a message that needs staff (or a verified provider event) can never be used to flood anyone, and must not be lost.
			if (r.type().customerTriggered() && notifications.countRecentForRecipient(hash, now.minus(Duration.ofHours(1))) >= perRecipientPerHour) {
				status = NotificationStatus.SKIPPED_THROTTLED;
			}
		}
		var id = UUID.randomUUID();
		notifications.insertIfAbsent(new Notification(id, UUID.randomUUID(), r.dedupeKey(), r.type(), r.reservationId(), r.paymentId(), r.attemptId(), r.locale() == null ? "fr" : r.locale(),
				hint, hash, status, 0, now, null, null, null, now, now, null));
	}
}
