package com.anadepot.infra.security;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_recovery_codes")
class StaffRecoveryCodeEntity {

	@Id
	UUID id;
	UUID userId;
	String codeHash;
	UUID batchId;
	Instant createdAt;
	Instant usedAt;
	Instant supersededAt;

	protected StaffRecoveryCodeEntity() {
	}
}
