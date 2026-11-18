package com.anadepot.core.staff.model;

import java.time.Instant;
import java.util.UUID;

/** A pending, accepted, revoked or expired invitation. The code itself is never stored, only its hash. */
public record StaffInvitation(UUID id, String codeHash, String note, UUID createdBy, Instant createdAt, Instant expiresAt,
		UUID acceptedBy, Instant acceptedAt, Instant revokedAt, UUID revokedBy) {

	public enum Status { PENDING, ACCEPTED, REVOKED, EXPIRED }

	public Status status(Instant now) {
		if (acceptedAt != null) {
			return Status.ACCEPTED;
		}
		if (revokedAt != null) {
			return Status.REVOKED;
		}
		return now.isBefore(expiresAt) ? Status.PENDING : Status.EXPIRED;
	}

	public StaffInvitation accepted(UUID by, Instant at) {
		return new StaffInvitation(id, codeHash, note, createdBy, createdAt, expiresAt, by, at, revokedAt, revokedBy);
	}

	public StaffInvitation revoked(UUID by, Instant at) {
		return new StaffInvitation(id, codeHash, note, createdBy, createdAt, expiresAt, acceptedBy, acceptedAt, at, by);
	}
}
