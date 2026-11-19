package com.anadepot.core.staff.serviceImpl;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.staff.model.RecoveryCode;
import com.anadepot.core.staff.model.RecoveryCodes;
import com.anadepot.core.staff.repository.StaffRecoveryRepository;
import com.anadepot.core.staff.repository.StaffRepository;
import com.anadepot.core.staff.service.StaffRecoveryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class StaffRecoveryServiceImpl implements StaffRecoveryService {

	public static final int CODE_COUNT = 10;
	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";   // no 0/O/1/I/L, as for invitation codes
	private static final int CODE_LENGTH = 16;                                  // 30^16 is about 2^78: not guessable, and attempts are rate limited as well

	private final StaffRecoveryRepository recovery;
	private final StaffRepository staff;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final Duration sessionTtl;
	private final SecureRandom random = new SecureRandom();

	public StaffRecoveryServiceImpl(StaffRecoveryRepository recovery, StaffRepository staff, AuditRecorder audit, TransactionBoundary tx, Clock clock, Duration sessionTtl) {
		this.recovery = recovery;
		this.staff = staff;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
		this.sessionTtl = sessionTtl;
	}

	@Override
	public RecoveryCodes.Issued generate(Actor actor) {
		requireActor(actor);
		var now = clock.instant();
		return tx.inTransaction(() -> {
			requireActiveMember(actor.userId());
			var replaced = recovery.unusedOf(actor.userId());
			for (var old : replaced) {
				recovery.save(old.superseded(now));
			}
			var batch = UUID.randomUUID();
			var clear = new ArrayList<String>();
			var rows = new ArrayList<RecoveryCode>();
			for (int i = 0; i < CODE_COUNT; i++) {
				var code = newCode();
				clear.add(display(code));
				rows.add(new RecoveryCode(UUID.randomUUID(), actor.userId(), hash(actor.userId(), code), batch, now, null, null));
			}
			recovery.saveAll(rows);
			audit.record(AuditEvent.of("STAFF_RECOVERY_CODES_GENERATED", "staff_member", actor.userId(), actor, Map.of("replaced", replaced.size()), Map.of("count", CODE_COUNT), null));
			return new RecoveryCodes.Issued(java.util.List.copyOf(clear), now);
		});
	}

	@Override
	public RecoveryCodes.Status status(UUID userId) {
		return tx.inTransaction(() -> new RecoveryCodes.Status(recovery.unusedCounts(java.util.List.of(userId)).getOrDefault(userId, 0), recovery.latestGeneratedAt(userId).orElse(null)));
	}

	@Override
	public RecoveryCodes.Grant redeem(String code, String sessionId, Actor actor) {
		if (actor == null || actor.userId() == null || sessionId == null || sessionId.isBlank() || sessionId.length() > 100) {
			throw invalid();
		}
		var normalized = normalize(code);
		if (normalized.length() != CODE_LENGTH) {
			throw invalid();
		}
		var now = clock.instant();
		// a refusal is audited AND committed, then reported: throwing inside the transaction would roll the audit row back with it
		var grant = tx.inTransaction(() -> {
			var member = staff.findMember(actor.userId());
			var stored = member.filter(m -> m.active()).flatMap(m -> recovery.lockByHash(hash(actor.userId(), normalized))).filter(RecoveryCode::unused);
			if (stored.isEmpty()) {
				if (member.isPresent() && member.get().active()) {
					audit.record(AuditEvent.of("STAFF_MFA_RECOVERY_REFUSED", "staff_member", actor.userId(), actor, Map.of(), Map.of(), null));
				}
				return (RecoveryCodes.Grant) null;
			}
			recovery.save(stored.get().used(now));
			var expiresAt = now.plus(sessionTtl);
			recovery.saveSession(UUID.randomUUID(), actor.userId(), sessionId, now, expiresAt);
			int remaining = recovery.unusedCounts(java.util.List.of(actor.userId())).getOrDefault(actor.userId(), 0);
			audit.record(AuditEvent.of("STAFF_MFA_RECOVERY_USED", "staff_member", actor.userId(), actor, Map.of("remaining", remaining + 1), Map.of("remaining", remaining, "expiresAt", expiresAt.toString()), null));
			return new RecoveryCodes.Grant(expiresAt, remaining);
		});
		if (grant == null) {
			throw invalid();
		}
		return grant;
	}

	@Override
	public boolean isRecoveredSession(UUID userId, String sessionId) {
		return userId != null && sessionId != null && !sessionId.isBlank() && tx.inTransaction(() -> recovery.hasLiveSession(userId, sessionId, clock.instant()));
	}

	@Override
	public Map<UUID, Integer> remaining(Collection<UUID> userIds) {
		return userIds.isEmpty() ? Map.of() : tx.inTransaction(() -> recovery.unusedCounts(userIds));
	}

	private void requireActor(Actor actor) {
		if (actor == null || actor.userId() == null) {
			throw new DomainRuleViolation("not_a_member", "Only a signed-in team member can make recovery codes.");
		}
	}

	private void requireActiveMember(UUID userId) {
		if (staff.findMember(userId).filter(m -> m.active()).isEmpty()) {
			throw new DomainRuleViolation("not_a_member", "Only a signed-in team member can make recovery codes.");
		}
	}

	private String newCode() {
		var sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	/** Groups of four: XXXX-XXXX-XXXX-XXXX. */
	private static String display(String code) {
		var sb = new StringBuilder();
		for (int i = 0; i < code.length(); i += 4) {
			if (i > 0) {
				sb.append('-');
			}
			sb.append(code, i, i + 4);
		}
		return sb.toString();
	}

	private static String normalize(String code) {
		return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
	}

	/** Bound to the member: the same code text hashes differently for another account. */
	private static String hash(UUID userId, String normalizedCode) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((userId + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static NotFoundException invalid() {
		return NotFoundException.withCode("recovery_code_invalid", "This recovery code is not valid. It may have been used already or replaced by a newer set.");
	}
}
