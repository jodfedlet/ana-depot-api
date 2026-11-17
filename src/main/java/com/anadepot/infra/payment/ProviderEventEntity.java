package com.anadepot.infra.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_provider_events")
class ProviderEventEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<UUID> {

	@Id
	UUID id;
	String provider;
	String eventId;
	String eventType;
	UUID attemptId;
	String outcome;
	Instant receivedAt;

	protected ProviderEventEntity() {
	}

	@Override
	public UUID getId() {
		return id;
	}
}
