package com.anadepot.infra.storage;

import com.anadepot.core.catalog.model.UploadStatus;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface UploadIntentJpaRepository extends JpaRepository<UploadIntentEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UploadIntentEntity u where u.id = :id")
	Optional<UploadIntentEntity> lockById(UUID id);

	List<UploadIntentEntity> findByStatusAndExpiresAtBefore(UploadStatus status, Instant cutoff);
}
