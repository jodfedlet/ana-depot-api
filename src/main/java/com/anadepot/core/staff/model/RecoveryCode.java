package com.anadepot.core.staff.model;

import java.time.Instant;
import java.util.UUID;

/** One recovery code of a member. Only the hash is ever stored; the code itself exists once, on the screen that created it. */
public record RecoveryCode(UUID id, UUID userId, String codeHash, UUID batchId, Instant createdAt, Instant usedAt, Instant supersededAt) {

	public RecoveryCode used(Instant at) {
		return new RecoveryCode(id, userId, codeHash, batchId, createdAt, at, supersededAt);
	}

	public RecoveryCode superseded(Instant at) {
		return new RecoveryCode(id, userId, codeHash, batchId, createdAt, usedAt, at);
	}

	public boolean unused() {
		return usedAt == null && supersededAt == null;
	}
}
