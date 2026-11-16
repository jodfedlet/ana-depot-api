package com.anadepot.infra.storage;

import com.anadepot.core.catalog.model.UploadStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_intents")
class UploadIntentEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<UUID> {

	@Id
	UUID id;
	String purpose;
	UUID resourceId;
	String objectKey;
	String declaredContentType;
	long maxBytes;
	@Enumerated(EnumType.STRING)
	UploadStatus status;
	Instant expiresAt;
	UUID requestedBy;
	Long verifiedSize;
	String verifiedContentType;
	Instant createdAt;
	Instant completedAt;

	protected UploadIntentEntity() {
	}

	@Override
	public UUID getId() {
		return id;
	}
}
