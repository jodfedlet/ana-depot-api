package com.anadepot.core.staff.model;

import java.time.Instant;
import java.util.UUID;

/** A person who works for the store, with one of the three fixed roles ({@link StaffRole}). {@code note} is what the inviter wrote. */
public record StaffMember(UUID userId, boolean active, StaffRole role, UUID grantedBy, Instant grantedAt, String note, UUID updatedBy, Instant updatedAt) {

	public StaffMember withActive(boolean active, UUID by, Instant at) {
		return new StaffMember(userId, active, role, grantedBy, grantedAt, note, by, at);
	}

	public StaffMember withRole(StaffRole role, UUID by, Instant at) {
		return new StaffMember(userId, active, role, grantedBy, grantedAt, note, by, at);
	}

	/** Reactivation through a new invitation: active again with the role that invitation carries. */
	public StaffMember rejoined(StaffRole role, UUID by, Instant at) {
		return new StaffMember(userId, true, role, grantedBy, grantedAt, note, by, at);
	}
}
