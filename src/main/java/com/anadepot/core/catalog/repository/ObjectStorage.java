package com.anadepot.core.catalog.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Port to an S3-compatible object store (Supabase Storage is the planned provider). The browser uploads DIRECTLY with a
 * short-lived presigned URL; permanent credentials never leave the backend. Provider SDKs live in infrastructure only.
 */
public interface ObjectStorage {

	/** A URL that allows exactly ONE PUT of {@code objectKey} with {@code contentType}, valid for {@code ttl}. */
	PresignedUpload presignPut(String objectKey, String contentType, long maxBytes, Duration ttl);

	/** What is actually stored under the key (size, content type, first bytes), or empty if nothing is there. */
	Optional<StoredObject> inspect(String objectKey, int headBytes);

	/** Idempotent: deleting a missing object is not an error. */
	void delete(String objectKey);

	String publicUrl(String objectKey);

	record PresignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
	}

	record StoredObject(long size, String contentType, byte[] head) {
	}
}
