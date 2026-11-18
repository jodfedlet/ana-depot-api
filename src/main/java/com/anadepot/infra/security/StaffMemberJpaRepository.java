package com.anadepot.infra.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StaffMemberJpaRepository extends JpaRepository<StaffMemberEntity, UUID> {

	boolean existsByUserIdAndActiveTrue(UUID userId);

	List<StaffMemberEntity> findAllByOrderByGrantedAtAsc();

	/** Every active member, locked: concurrent removals serialize, so the last active member can never be removed by two simultaneous requests. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from StaffMemberEntity m where m.active = true order by m.userId")
	List<StaffMemberEntity> lockActive();
}
