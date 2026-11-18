package com.anadepot.infra.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StaffInvitationJpaRepository extends JpaRepository<StaffInvitationEntity, UUID> {

	List<StaffInvitationEntity> findAllByOrderByCreatedAtDesc();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from StaffInvitationEntity i where i.codeHash = :hash")
	Optional<StaffInvitationEntity> lockByCodeHash(@Param("hash") String hash);
}
