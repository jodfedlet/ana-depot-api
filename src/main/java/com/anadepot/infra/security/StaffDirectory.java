package com.anadepot.infra.security;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import com.anadepot.core.staff.model.StaffRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY source of truth for "is this person staff": a row in {@code staff_members}. Never a JWT claim, never
 * user-editable metadata. The role (D14) is in the same row.
 */
@Component
public class StaffDirectory {

	private final StaffMemberJpaRepository repository;
	private final Clock clock;

	StaffDirectory(StaffMemberJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public boolean isActiveStaff(UUID userId) {
		return userId != null && repository.existsByUserIdAndActiveTrue(userId);
	}

	/** The role of an ACTIVE member, from OUR table; empty for everyone else. */
	@Transactional(readOnly = true)
	public Optional<StaffRole> activeRole(UUID userId) {
		return userId == null ? Optional.empty() : repository.findById(userId).filter(m -> m.active).map(m -> m.role);
	}

	@Transactional(readOnly = true)
	public boolean hasAnyStaff() {
		return repository.count() > 0;
	}

	@Transactional
	public void deactivate(UUID userId) {
		repository.findById(userId).ifPresent(row -> row.active = false);
	}

	@Transactional
	public void grant(UUID userId, UUID grantedBy, String note) {
		grant(userId, grantedBy, note, StaffRole.OWNER);   // the first administrator and every pre-roles member: OWNER
	}

	@Transactional
	public void grant(UUID userId, UUID grantedBy, String note, StaffRole role) {
		var row = new StaffMemberEntity();
		row.role = role;
		row.userId = userId;
		row.active = true;
		row.grantedBy = grantedBy;
		row.grantedAt = clock.instant();
		row.note = note;
		repository.save(row);
	}
}
