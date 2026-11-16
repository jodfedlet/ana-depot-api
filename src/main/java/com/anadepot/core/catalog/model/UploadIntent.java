package com.anadepot.core.catalog.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The server's record of "this person may upload ONE object, with this key, of this type, up to this size, until this time".
 * The client never chooses the object key.
 */
public record UploadIntent(UUID id, UUID productId, String objectKey, String declaredContentType, long maxBytes, UploadStatus status,
		Instant expiresAt, UUID requestedBy, Long verifiedSize, String verifiedContentType, Instant createdAt, Instant completedAt) {

	public UploadIntent with(UploadStatus next, Long size, String contentType, Instant at) {
		return new UploadIntent(id, productId, objectKey, declaredContentType, maxBytes, next, expiresAt, requestedBy, size, contentType, createdAt, at);
	}
}
