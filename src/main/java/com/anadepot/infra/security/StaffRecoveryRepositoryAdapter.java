package com.anadepot.infra.security;

import com.anadepot.core.staff.model.RecoveryCode;
import com.anadepot.core.staff.repository.StaffRecoveryRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class StaffRecoveryRepositoryAdapter implements StaffRecoveryRepository {

	private final StaffRecoveryCodeJpaRepository codes;
	private final StaffRecoverySessionJpaRepository sessions;

	StaffRecoveryRepositoryAdapter(StaffRecoveryCodeJpaRepository codes, StaffRecoverySessionJpaRepository sessions) {
		this.codes = codes;
		this.sessions = sessions;
	}

	@Override
	public void saveAll(List<RecoveryCode> list) {
		list.forEach(this::save);
	}

	@Override
	public RecoveryCode save(RecoveryCode c) {
		var e = codes.findById(c.id()).orElseGet(StaffRecoveryCodeEntity::new);
		e.id = c.id();
		e.userId = c.userId();
		e.codeHash = c.codeHash();
		e.batchId = c.batchId();
		e.createdAt = c.createdAt();
		e.usedAt = c.usedAt();
		e.supersededAt = c.supersededAt();
		return toDomain(codes.saveAndFlush(e));
	}

	@Override
	public List<RecoveryCode> unusedOf(UUID userId) {
		return codes.findAllByUserIdAndUsedAtIsNullAndSupersededAtIsNull(userId).stream().map(StaffRecoveryRepositoryAdapter::toDomain).toList();
	}

	@Override
	public Optional<RecoveryCode> lockByHash(String codeHash) {
		return codes.lockByCodeHash(codeHash).map(StaffRecoveryRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Instant> latestGeneratedAt(UUID userId) {
		return codes.latestCreatedAt(userId);
	}

	@Override
	public Map<UUID, Integer> unusedCounts(Collection<UUID> userIds) {
		var out = new HashMap<UUID, Integer>();
		codes.unusedCounts(userIds).forEach(r -> out.put(r.getUserId(), (int) r.getCount()));
		return out;
	}

	@Override
	public void saveSession(UUID id, UUID userId, String sessionId, Instant createdAt, Instant expiresAt) {
		var e = new StaffRecoverySessionEntity();
		e.id = id;
		e.userId = userId;
		e.sessionId = sessionId;
		e.createdAt = createdAt;
		e.expiresAt = expiresAt;
		sessions.saveAndFlush(e);
	}

	@Override
	public boolean hasLiveSession(UUID userId, String sessionId, Instant now) {
		return sessions.existsByUserIdAndSessionIdAndExpiresAtAfter(userId, sessionId, now);
	}

	private static RecoveryCode toDomain(StaffRecoveryCodeEntity e) {
		return new RecoveryCode(e.id, e.userId, e.codeHash, e.batchId, e.createdAt, e.usedAt, e.supersededAt);
	}
}
