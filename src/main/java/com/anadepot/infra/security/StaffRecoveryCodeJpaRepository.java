package com.anadepot.infra.security;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StaffRecoveryCodeJpaRepository extends JpaRepository<StaffRecoveryCodeEntity, UUID> {

	interface UnusedCount {
		UUID getUserId();

		long getCount();
	}

	List<StaffRecoveryCodeEntity> findAllByUserIdAndUsedAtIsNullAndSupersededAtIsNull(UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from StaffRecoveryCodeEntity c where c.codeHash = :hash")
	Optional<StaffRecoveryCodeEntity> lockByCodeHash(@Param("hash") String hash);

	@Query("select max(c.createdAt) from StaffRecoveryCodeEntity c where c.userId = :userId")
	Optional<Instant> latestCreatedAt(@Param("userId") UUID userId);

	@Query("select c.userId as userId, count(c) as count from StaffRecoveryCodeEntity c where c.userId in :ids and c.usedAt is null and c.supersededAt is null group by c.userId")
	List<UnusedCount> unusedCounts(@Param("ids") Collection<UUID> ids);
}
