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
@Table(name = "staff_members")
class StaffMemberEntity {

	@Id
	UUID userId;
	boolean active;
	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	StaffRole role;
	UUID grantedBy;
	Instant grantedAt;
	String note;
	UUID updatedBy;
	Instant updatedAt;

	protected StaffMemberEntity() {
	}
}
