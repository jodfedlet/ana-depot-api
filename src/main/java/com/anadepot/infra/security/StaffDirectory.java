package com.anadepot.infra.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY source of truth for "is this person staff": a row in {@code staff_members}. Never a JWT claim, never
 * user-editable metadata. Roles/permissions are an undecided business matter, so membership is all-or-nothing today.
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

	@Transactional(readOnly = true)
	public boolean hasAnyStaff() {
		return repository.count() > 0;
	}

	@Transactional
	public void grant(UUID userId, UUID grantedBy, String note) {
		var row = new StaffMemberEntity();
		row.userId = userId;
		row.active = true;
		row.grantedBy = grantedBy;
		row.grantedAt = clock.instant();
		row.note = note;
		repository.save(row);
	}
}
