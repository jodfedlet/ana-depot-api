package com.anadepot.core.staff.repository;

import com.anadepot.core.staff.model.RecoveryCode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for staff recovery codes and the sessions that redeemed one. */
public interface StaffRecoveryRepository {

	void saveAll(List<RecoveryCode> codes);

	List<RecoveryCode> unusedOf(UUID userId);

	/** The code with this (member-bound) hash, locked: two people redeeming one code at once, only one wins. */
	Optional<RecoveryCode> lockByHash(String codeHash);

	RecoveryCode save(RecoveryCode code);

	Optional<Instant> latestGeneratedAt(UUID userId);

	Map<UUID, Integer> unusedCounts(Collection<UUID> userIds);

	void saveSession(UUID id, UUID userId, String sessionId, Instant createdAt, Instant expiresAt);

	boolean hasLiveSession(UUID userId, String sessionId, Instant now);
}
