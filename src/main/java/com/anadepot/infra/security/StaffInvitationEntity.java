package com.anadepot.infra.security;

import com.anadepot.core.staff.model.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	StaffRole role;
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
