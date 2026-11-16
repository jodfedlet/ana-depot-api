package com.anadepot.infra.storage;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.infra.config.AnaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the object store. A COMPLETE Cloudflare R2 configuration creates {@link R2ObjectStorage}; anything less (nothing set, or only some of the
 * variables) leaves the fail-closed {@link UnconfiguredObjectStorage}: uploads are refused with {@code storage_not_configured}, nothing pretends to work.
 */
@Configuration
class StorageConfig {

	private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

	@Bean
	@ConditionalOnMissingBean(ObjectStorage.class)
	ObjectStorage objectStorage(AnaProperties properties) {
		var r2 = properties.storage().r2();
		if (!r2.anySet()) {
			return new UnconfiguredObjectStorage();
		}
		if (!r2.complete()) {
			log.error("Cloudflare R2 is only partly configured; uploads stay disabled. Missing: {}", r2.missing());
			return new UnconfiguredObjectStorage();
		}
		if (properties.upload().urlTtl().compareTo(R2ObjectStorage.MAX_PRESIGN_TTL) > 0 || properties.upload().urlTtl().isZero()) {
			throw new IllegalStateException("ana.upload.url-ttl (R2_PRESIGNED_URL_EXPIRATION_SECONDS) must be between 1 second and 7 days for Cloudflare R2");
		}
		log.info("Object storage: Cloudflare R2 {}", r2);          // toString redacts the credentials
		return new R2ObjectStorage(r2.effectiveEndpoint(), "auto", r2.bucketName().strip(), r2.accessKeyId().strip(), r2.secretAccessKey().strip(), r2.publicBaseUrl(), r2.pathStyleAccess());
	}
}
