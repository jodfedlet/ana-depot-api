package com.anadepot.infra.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
class ProcessedEventEntity {

	@Id
	UUID id;
	String consumer;
	String eventId;
	Instant firstProcessedAt;
	int duplicateCount;
	Instant lastDuplicateAt;

	protected ProcessedEventEntity() {
	}
}
