package com.anadepot.infra.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, UUID> {

	Optional<ProcessedEventEntity> findByConsumerAndEventId(String consumer, String eventId);

	@Modifying
	@Query("update ProcessedEventEntity p set p.duplicateCount = p.duplicateCount + 1, p.lastDuplicateAt = :now where p.consumer = :consumer and p.eventId = :eventId")
	int markDuplicate(String consumer, String eventId, Instant now);
}
