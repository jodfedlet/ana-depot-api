package com.anadepot.infra.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
class IdempotencyKeyEntity {

	@Id
	UUID id;
	String actorScope;
	String operation;
	String idempotencyKey;
	String requestFingerprint;
	UUID resourceId;
	Instant createdAt;
	Instant expiresAt;

	protected IdempotencyKeyEntity() {
	}
}
