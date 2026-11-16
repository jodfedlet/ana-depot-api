package com.anadepot.infra.storage;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.DomainRuleViolation;
import java.time.Duration;
import java.util.Optional;

/**
 * Default storage until a provider adapter exists (Supabase Storage / S3 is planned): every call fails clearly. It is
 * never a fake that pretends an upload happened.
 */
public class UnconfiguredObjectStorage implements ObjectStorage {

	private static DomainRuleViolation unavailable() {
		return new DomainRuleViolation("storage_not_configured", "Object storage is not configured");
	}

	@Override
	public PresignedUpload presignPut(String objectKey, String contentType, long maxBytes, Duration ttl) {
		throw unavailable();
	}

	@Override
	public Optional<StoredObject> inspect(String objectKey, int headBytes) {
		throw unavailable();
	}

	@Override
	public void delete(String objectKey) {
		throw unavailable();
	}

	@Override
	public String publicUrl(String objectKey) {
		throw unavailable();
	}
}
