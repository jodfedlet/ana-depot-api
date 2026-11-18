package com.anadepot.infra.security;

import jakarta.persistence.Entity;
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
	UUID grantedBy;
	Instant grantedAt;
	String note;
	UUID updatedBy;
	Instant updatedAt;

	protected StaffMemberEntity() {
	}
}
