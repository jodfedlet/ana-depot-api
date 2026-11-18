package com.anadepot.core.staff.model;

import java.time.Instant;
import java.util.UUID;

/** A person who works for the store. Membership is all-or-nothing: roles and permissions are an open business decision. {@code note} is what the inviter wrote. */
public record StaffMember(UUID userId, boolean active, UUID grantedBy, Instant grantedAt, String note, UUID updatedBy, Instant updatedAt) {

	public StaffMember withActive(boolean active, UUID by, Instant at) {
		return new StaffMember(userId, active, grantedBy, grantedAt, note, by, at);
	}
}
