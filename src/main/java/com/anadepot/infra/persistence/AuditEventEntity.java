package com.anadepot.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

	@Id
	UUID id;
	Instant occurredAt;
	String actorKind;
	UUID actorUserId;
	String action;
	String targetType;
	String targetId;
	@Column(columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	String previousValues;
	@Column(columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	String newValues;
	String reason;
	String correlationId;

	protected AuditEventEntity() {
	}

	public UUID getId() { return id; }
	public Instant getOccurredAt() { return occurredAt; }
	public String getActorKind() { return actorKind; }
	public UUID getActorUserId() { return actorUserId; }
	public String getAction() { return action; }
	public String getTargetType() { return targetType; }
	public String getTargetId() { return targetId; }
	public String getPreviousValues() { return previousValues; }
	public String getNewValues() { return newValues; }
	public String getReason() { return reason; }
	public String getCorrelationId() { return correlationId; }
}
