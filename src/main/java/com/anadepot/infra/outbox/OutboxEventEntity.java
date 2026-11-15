package com.anadepot.infra.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "outbox_events")
class OutboxEventEntity {

	@Id
	UUID id;
	@Generated(event = EventType.INSERT)
	@Column(insertable = false, updatable = false)
	Long seq;
	String eventType;
	String aggregateType;
	UUID aggregateId;
	long aggregateVersion;
	@Column(columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	String payload;
	String correlationId;
	Instant createdAt;
	String status;
	int attempts;
	Instant nextAttemptAt;
	Instant leaseUntil;
	String lastError;
	Instant processedAt;

	protected OutboxEventEntity() {
	}
}
