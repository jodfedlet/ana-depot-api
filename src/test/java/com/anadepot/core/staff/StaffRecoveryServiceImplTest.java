package com.anadepot.core.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.staff.model.RecoveryCode;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.model.StaffRole;
import com.anadepot.core.staff.repository.StaffRecoveryRepository;
import com.anadepot.core.staff.serviceImpl.StaffRecoveryServiceImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recovery-code rules in plain Java (no Spring, no database). */
class StaffRecoveryServiceImplTest {

	static final class MemoryRecovery implements StaffRecoveryRepository {
		final Map<UUID, RecoveryCode> codes = new LinkedHashMap<>();
		final List<Object[]> sessions = new ArrayList<>();
		@Override public void saveAll(List<RecoveryCode> list) { list.forEach(c -> codes.put(c.id(), c)); }
		@Override public List<RecoveryCode> unusedOf(UUID userId) { return codes.values().stream().filter(c -> c.userId().equals(userId) && c.unused()).toList(); }
		@Override public Optional<RecoveryCode> lockByHash(String hash) { return codes.values().stream().filter(c -> c.codeHash().equals(hash)).findFirst(); }
		@Override public RecoveryCode save(RecoveryCode c) { codes.put(c.id(), c); return c; }
		@Override public Optional<Instant> latestGeneratedAt(UUID userId) { return codes.values().stream().filter(c -> c.userId().equals(userId)).map(RecoveryCode::createdAt).max(Instant::compareTo); }
		@Override public Map<UUID, Integer> unusedCounts(Collection<UUID> ids) { var m = new HashMap<UUID, Integer>(); codes.values().stream().filter(c -> ids.contains(c.userId()) && c.unused()).forEach(c -> m.merge(c.userId(), 1, Integer::sum)); return m; }
		@Override public void saveSession(UUID id, UUID userId, String sessionId, Instant createdAt, Instant expiresAt) { sessions.add(new Object[] { userId, sessionId, expiresAt }); }
		@Override public boolean hasLiveSession(UUID userId, String sessionId, Instant now) { return sessions.stream().anyMatch(s -> s[0].equals(userId) && s[1].equals(sessionId) && ((Instant) s[2]).isAfter(now)); }
	}

	StaffServiceImplTest.MutableClock clock;
	MemoryRecovery recovery;
	StaffServiceImplTest.MemoryRepository staff;
	List<AuditEvent> audit;
	StaffRecoveryServiceImpl service;
	final Actor alice = Actor.staff(UUID.randomUUID());
	final Actor bob = Actor.staff(UUID.randomUUID());

	@BeforeEach
	void setUp() {
		clock = new StaffServiceImplTest.MutableClock();
		recovery = new MemoryRecovery();
		staff = new StaffServiceImplTest.MemoryRepository();
		audit = new ArrayList<>();
		AuditRecorder recorder = audit::add;
		TransactionBoundary tx = new TransactionBoundary() { @Override public <T> T inTransaction(Supplier<T> work) { return work.get(); } };
		service = new StaffRecoveryServiceImpl(recovery, staff, recorder, tx, clock, Duration.ofHours(4));
		for (var a : List.of(alice, bob)) {
			staff.members.put(a.userId(), new StaffMember(a.userId(), true, StaffRole.OWNER, null, clock.now, "m", null, null));
		}
	}

	@Test
	void tenDistinctCodesAreMadeOnlyTheirHashesAreStoredAndTheAuditHoldsNone() {
		var issued = service.generate(alice);
		assertThat(issued.codes()).hasSize(10).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"));
		assertThat(issued.toString()).doesNotContain(issued.codes().get(0));
		assertThat(recovery.codes.values()).hasSize(10).allSatisfy(c -> assertThat(c.codeHash()).hasSize(64));
		issued.codes().forEach(code -> {
			assertThat(recovery.codes.values()).noneMatch(c -> c.codeHash().contains(code.replace("-", "")));
			assertThat(audit.toString()).doesNotContain(code).doesNotContain(code.replace("-", ""));
		});
		assertThat(audit).extracting(AuditEvent::action).containsExactly("STAFF_RECOVERY_CODES_GENERATED");
		assertThat(service.status(alice.userId()).remaining()).isEqualTo(10);
	}

	@Test
	void aCodeWorksExactlyOnce_forThatSessionOnly_andIsAudited() {
		var codes = service.generate(alice).codes();
		var grant = service.redeem(codes.get(0).toLowerCase(), "session-1", alice);   // case and dashes do not matter
		assertThat(grant.remaining()).isEqualTo(9);
		assertThat(grant.expiresAt()).isEqualTo(clock.now.plus(Duration.ofHours(4)));
		assertThat(service.isRecoveredSession(alice.userId(), "session-1")).isTrue();
		assertThat(service.isRecoveredSession(alice.userId(), "session-2")).as("another sign-in session gets nothing").isFalse();
		assertThat(service.isRecoveredSession(bob.userId(), "session-1")).isFalse();
		assertThatThrownBy(() -> service.redeem(codes.get(0), "session-2", alice)).isInstanceOf(NotFoundException.class).extracting(e -> ((NotFoundException) e).code()).isEqualTo("recovery_code_invalid");
		assertThat(service.isRecoveredSession(alice.userId(), "session-2")).isFalse();
		assertThat(audit).extracting(AuditEvent::action).contains("STAFF_MFA_RECOVERY_USED", "STAFF_MFA_RECOVERY_REFUSED");
		assertThat(audit.toString()).doesNotContain(codes.get(0).replace("-", ""));
	}

	@Test
	void theGrantEndsWithItsTime() {
		var codes = service.generate(alice).codes();
		service.redeem(codes.get(0), "s", alice);
		clock.now = clock.now.plus(Duration.ofHours(4)).minusSeconds(1);
		assertThat(service.isRecoveredSession(alice.userId(), "s")).isTrue();
		clock.now = clock.now.plusSeconds(2);
		assertThat(service.isRecoveredSession(alice.userId(), "s")).isFalse();
	}

	@Test
	void aCodeBelongsToItsMemberAndEveryInvalidAttemptLooksTheSame() {
		var aliceCodes = service.generate(alice).codes();
		assertThatThrownBy(() -> service.redeem(aliceCodes.get(0), "s", bob)).isInstanceOf(NotFoundException.class);   // bob cannot use alice's code
		assertThatThrownBy(() -> service.redeem("AAAA-BBBB-CCCC-DDDD", "s", alice)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redeem("short", "s", alice)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redeem(null, "s", alice)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redeem(aliceCodes.get(0), null, alice)).isInstanceOf(NotFoundException.class);   // no session id in the token: nothing to bind to
		assertThatThrownBy(() -> service.redeem(aliceCodes.get(0), "  ", alice)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redeem(aliceCodes.get(0), "s", null)).isInstanceOf(NotFoundException.class);
		assertThat(service.status(alice.userId()).remaining()).as("nothing was consumed by the failures").isEqualTo(10);
	}

	@Test
	void aNewSetReplacesTheOldUnusedCodesAndUsedCodesStayUsed() {
		var first = service.generate(alice).codes();
		service.redeem(first.get(0), "s", alice);
		var second = service.generate(alice).codes();
		assertThat(service.status(alice.userId()).remaining()).isEqualTo(10);
		assertThatThrownBy(() -> service.redeem(first.get(1), "s2", alice)).as("a replaced code is dead").isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redeem(first.get(0), "s2", alice)).isInstanceOf(NotFoundException.class);
		service.redeem(second.get(0), "s2", alice);
		assertThat(audit.stream().filter(e -> e.action().equals("STAFF_RECOVERY_CODES_GENERATED"))).hasSize(2);
	}

	@Test
	void onlyAnActiveMemberMakesOrRedeemsCodes() {
		var stranger = Actor.staff(UUID.randomUUID());
		assertThatThrownBy(() -> service.generate(stranger)).isInstanceOf(DomainRuleViolation.class);
		assertThatThrownBy(() -> service.generate(null)).isInstanceOf(DomainRuleViolation.class);
		var codes = service.generate(alice).codes();
		staff.members.put(alice.userId(), staff.members.get(alice.userId()).withActive(false, null, clock.now));   // access removed after the codes were made
		assertThatThrownBy(() -> service.redeem(codes.get(0), "s", alice)).isInstanceOf(NotFoundException.class);
		assertThat(service.isRecoveredSession(alice.userId(), "s")).isFalse();
	}

	@Test
	void ownersSeeHowManyCodesEachMemberHasNeverTheCodes() {
		service.generate(alice);
		var counts = service.remaining(List.of(alice.userId(), bob.userId()));
		assertThat(counts.get(alice.userId())).isEqualTo(10);
		assertThat(counts.getOrDefault(bob.userId(), 0)).isZero();
		assertThat(service.remaining(List.of())).isEmpty();
	}
}
