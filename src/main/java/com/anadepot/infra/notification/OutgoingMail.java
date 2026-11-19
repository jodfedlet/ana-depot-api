package com.anadepot.infra.notification;

import java.util.UUID;

/** A fully rendered message ready for a transport. {@code notificationId} correlates the message with its queue row (header X-Ana-Notification-Id and the Message-ID). */
record OutgoingMail(UUID notificationId, String to, String subject, String html, String text) {

	@Override
	public String toString() {
		return "OutgoingMail[" + notificationId + "]";   // never the address or the body
	}
}
