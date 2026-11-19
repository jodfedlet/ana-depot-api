package com.anadepot.core.notification.model;

import java.util.UUID;

/** Staff list filters; every field optional. */
public record NotificationQuery(NotificationStatus status, NotificationType type, UUID reservationId) {
}
