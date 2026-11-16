package com.anadepot.infra.storage;

import com.anadepot.core.catalog.model.UploadIntent;
import com.anadepot.core.catalog.model.UploadStatus;
import com.anadepot.core.catalog.repository.UploadIntentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class UploadIntentRepositoryAdapter implements UploadIntentRepository {

	private final UploadIntentJpaRepository jpa;

	UploadIntentRepositoryAdapter(UploadIntentJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public UploadIntent save(UploadIntent i) {
		var e = jpa.findById(i.id()).orElseGet(UploadIntentEntity::new);
		e.id = i.id();
		e.purpose = "PRODUCT_IMAGE";
		e.resourceId = i.productId();
		e.objectKey = i.objectKey();
		e.declaredContentType = i.declaredContentType();
		e.maxBytes = i.maxBytes();
		e.status = i.status();
		e.expiresAt = i.expiresAt();
		e.requestedBy = i.requestedBy();
		e.verifiedSize = i.verifiedSize();
		e.verifiedContentType = i.verifiedContentType();
		e.createdAt = i.createdAt();
		e.completedAt = i.completedAt();
		return toDomain(jpa.saveAndFlush(e));
	}

	@Override
	public Optional<UploadIntent> findById(UUID id) {
		return jpa.findById(id).map(UploadIntentRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<UploadIntent> findByIdForUpdate(UUID id) {
		return jpa.lockById(id).map(UploadIntentRepositoryAdapter::toDomain);
	}

	@Override
	public List<UploadIntent> findStaleRequested(Instant cutoff) {
		return jpa.findByStatusAndExpiresAtBefore(UploadStatus.REQUESTED, cutoff).stream().map(UploadIntentRepositoryAdapter::toDomain).toList();
	}

	private static UploadIntent toDomain(UploadIntentEntity e) {
		return new UploadIntent(e.id, e.resourceId, e.objectKey, e.declaredContentType, e.maxBytes, e.status, e.expiresAt, e.requestedBy, e.verifiedSize,
				e.verifiedContentType, e.createdAt, e.completedAt);
	}
}
