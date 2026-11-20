package com.anadepot.infra.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class NotificationEntity {

	@Id
	UUID id;
	UUID eventId;
	String dedupeKey;
	String type;
	String aggregateType;
	UUID aggregateId;
	UUID reservationId;
	UUID paymentId;
	UUID attemptId;
	String locale;
	String recipientKind;
	String recipientHint;
	String recipientHash;
	String status;
	int attempts;
	Instant nextAttemptAt;
	Instant leaseUntil;
	String lastErrorCode;
	Instant lastErrorAt;
	Instant sentAt;
	Instant createdAt;
	Instant updatedAt;
	Instant revivedAt;

	protected NotificationEntity() {
	}
}
