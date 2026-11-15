package com.anadepot.infra.security;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StaffMemberJpaRepository extends JpaRepository<StaffMemberEntity, UUID> {

	boolean existsByUserIdAndActiveTrue(UUID userId);
}
