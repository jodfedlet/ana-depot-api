package com.anadepot.infra.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StaffRecoverySessionJpaRepository extends JpaRepository<StaffRecoverySessionEntity, UUID> {

	boolean existsByUserIdAndSessionIdAndExpiresAtAfter(UUID userId, String sessionId, Instant now);
}
