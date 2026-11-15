package com.anadepot.infra.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

	Optional<IdempotencyKeyEntity> findByActorScopeAndOperationAndIdempotencyKey(String actorScope, String operation, String idempotencyKey);

	@Modifying
	@Query("delete from IdempotencyKeyEntity k where k.expiresAt < :now")
	int deleteExpired(Instant now);
}
