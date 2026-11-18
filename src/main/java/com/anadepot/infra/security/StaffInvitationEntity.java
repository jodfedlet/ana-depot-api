package com.anadepot.infra.security;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_invitations")
class StaffInvitationEntity {

	@Id
	UUID id;
	String codeHash;
	String note;
	UUID createdBy;
	Instant createdAt;
	Instant expiresAt;
	UUID acceptedBy;
	Instant acceptedAt;
	Instant revokedAt;
	UUID revokedBy;

	protected StaffInvitationEntity() {
	}
}
