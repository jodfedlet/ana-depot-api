package com.anadepot.infra.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {

	List<AuditEventEntity> findByTargetTypeAndTargetIdOrderByOccurredAtAsc(String targetType, String targetId);
}
