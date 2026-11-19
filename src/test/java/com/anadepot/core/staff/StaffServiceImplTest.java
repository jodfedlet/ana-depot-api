package com.anadepot.core.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.model.StaffRole;
import com.anadepot.core.staff.repository.StaffRepository;
import com.anadepot.core.staff.serviceImpl.StaffServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The staff rules in plain Java: no Spring, no database. */
class StaffServiceImplTest {

	/** A clock the test can move. */
	static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-18T12:00:00Z");
		@Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(java.time.ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	static final class MemoryRepository implements StaffRepository {
		final Map<UUID, StaffMember> members = new LinkedHashMap<>();
		final Map<UUID, StaffInvitation> invitations = new LinkedHashMap<>();
		@Override public List<StaffMember> findAllMembers() { return List.copyOf(members.values()); }
		@Override public Optional<StaffMember> findMember(UUID id) { return Optional.ofNullable(members.get(id)); }
		@Override public List<StaffMember> lockActiveMembers() { return members.values().stream().filter(StaffMember::active).toList(); }
		@Override public StaffMember saveMember(StaffMember m) { members.put(m.userId(), m); return m; }
		@Override public List<StaffInvitation> findInvitations() { return List.copyOf(invitations.values()); }
		@Override public Optional<StaffInvitation> findInvitation(UUID id) { return Optional.ofNullable(invitations.get(id)); }
		@Override public Optional<StaffInvitation> lockInvitationByHash(String hash) { return invitations.values().stream().filter(i -> i.codeHash().equals(hash)).findFirst(); }
		@Override public StaffInvitation saveInvitation(StaffInvitation i) { invitations.put(i.id(), i); return i; }
	}

	MemoryRepository repo;
	MutableClock clock;
	List<AuditEvent> audit;
	StaffServiceImpl service;
	final Actor owner = Actor.staff(UUID.randomUUID());

	@BeforeEach
	void setUp() {
		repo = new MemoryRepository();
		clock = new MutableClock();
		audit = new ArrayList<>();
		AuditRecorder recorder = audit::add;
		TransactionBoundary tx = new TransactionBoundary() {
			@Override public <T> T inTransaction(Supplier<T> work) { return work.get(); }
		};
		service = new StaffServiceImpl(repo, recorder, tx, clock, Duration.ofDays(7));
		repo.members.put(owner.userId(), new StaffMember(owner.userId(), true, StaffRole.OWNER, null, clock.now, "owner", null, null));
	}

	@Test
	void theCodeIsNeverStoredOnlyItsHashAndTheAuditNeverHoldsItOrTheNote() {
		var issued = service.invite("Marie, cash desk", StaffRole.STAFF, owner);
		var stored = repo.invitations.values().iterator().next();
		assertThat(stored.codeHash()).hasSize(64).doesNotContain(issued.code().replace("-", ""));
		assertThat(issued.toString()).doesNotContain(issued.code());
		assertThat(audit.toString()).doesNotContain(issued.code()).doesNotContain(issued.code().replace("-", "")).doesNotContain("Marie");
		assertThat(issued.invitation().expiresAt()).isEqualTo(clock.now.plus(Duration.ofDays(7)));
	}

	@Test
	void anInvitationExpiresAndThenLooksLikeAnyOtherInvalidCode() {
		var issued = service.invite("late", StaffRole.STAFF, owner);
		clock.now = clock.now.plus(Duration.ofDays(7)).plusSeconds(1);
		assertThatThrownBy(() -> service.accept(issued.code(), Actor.staff(UUID.randomUUID()))).isInstanceOf(NotFoundException.class).extracting(e -> ((NotFoundException) e).code()).isEqualTo("invitation_invalid");
		assertThat(repo.members).hasSize(1);
	}

	@Test
	void aCodeWorksOnceAndTheNewMemberIsActiveWithTheInviterRecorded() {
		var issued = service.invite("Jean", StaffRole.STAFF, owner);
		var newcomer = Actor.staff(UUID.randomUUID());
		var member = service.accept(issued.code(), newcomer);
		assertThat(member.active()).isTrue();
		assertThat(member.grantedBy()).isEqualTo(owner.userId());
		assertThat(member.note()).isEqualTo("Jean");
		assertThatThrownBy(() -> service.accept(issued.code(), Actor.staff(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
		assertThat(audit).extracting(AuditEvent::action).contains("STAFF_INVITED", "STAFF_JOINED");
	}

	@Test
	void aGuestOrAnAnonymousActorCannotRedeem() {
		var issued = service.invite("x", StaffRole.STAFF, owner);
		assertThatThrownBy(() -> service.accept(issued.code(), Actor.guest())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.accept(issued.code(), null)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void aDeactivatedMemberCanComeBackWithANewInvitation() {
		var person = UUID.randomUUID();
		var first = service.accept(service.invite("first", StaffRole.STAFF, owner).code(), Actor.staff(person));
		service.setActive(person, false, owner);
		var again = service.accept(service.invite("again", StaffRole.STAFF, owner).code(), Actor.staff(person));
		assertThat(again.active()).isTrue();
		assertThat(first.userId()).isEqualTo(again.userId());
	}

	@Test
	void nobodyChangesTheirOwnAccessAndTheLastActiveMemberIsProtected() {
		assertThatThrownBy(() -> service.setActive(owner.userId(), false, owner)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("cannot_change_own_access");

		var second = Actor.staff(UUID.randomUUID());
		repo.members.put(second.userId(), new StaffMember(second.userId(), true, StaffRole.OWNER, null, clock.now, "second", null, null));
		service.setActive(second.userId(), false, owner);                                   // two active -> one: fine
		// only the owner is active now: a colleague (inactive, but acting through an old token) cannot remove the last active member either
		assertThatThrownBy(() -> service.setActive(owner.userId(), false, second)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("last_active_owner");
		assertThat(repo.members.get(owner.userId()).active()).isTrue();
		assertThat(audit).extracting(AuditEvent::action).contains("STAFF_DEACTIVATED");
	}

	@Test
	void anUnknownMemberIsNotFoundAndANoteIsRequired() {
		assertThatThrownBy(() -> service.setActive(UUID.randomUUID(), false, owner)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.invite("  ", StaffRole.STAFF, owner)).isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> service.invite("x".repeat(81), StaffRole.STAFF, owner)).isInstanceOf(FieldValidationException.class);
	}

	@Test
	void theInvitationCarriesTheRoleThatTheNewMemberReceives_andARoleIsRequired() {
		var manager = service.accept(service.invite("Marie", StaffRole.MANAGER, owner).code(), Actor.staff(UUID.randomUUID()));
		var counter = service.accept(service.invite("Jean", StaffRole.STAFF, owner).code(), Actor.staff(UUID.randomUUID()));
		assertThat(manager.role()).isEqualTo(StaffRole.MANAGER);
		assertThat(counter.role()).isEqualTo(StaffRole.STAFF);
		assertThat(audit.toString()).contains("MANAGER");
		assertThatThrownBy(() -> service.invite("nobody", null, owner)).isInstanceOf(FieldValidationException.class);
	}

	@Test
	void aMemberWhoComesBackGetsTheRoleOfTheNewInvitation() {
		var person = UUID.randomUUID();
		service.accept(service.invite("first", StaffRole.MANAGER, owner).code(), Actor.staff(person));
		service.setActive(person, false, owner);
		var again = service.accept(service.invite("again", StaffRole.STAFF, owner).code(), Actor.staff(person));
		assertThat(again.role()).isEqualTo(StaffRole.STAFF);
	}

	@Test
	void theLastActiveOwnerCanNeitherBeRemovedNorDemoted_butOtherRolesCanBe() {
		var manager = Actor.staff(UUID.randomUUID());
		repo.members.put(manager.userId(), new StaffMember(manager.userId(), true, StaffRole.MANAGER, null, clock.now, "manager", null, null));
		// the manager acts (old token) against the only owner
		assertThatThrownBy(() -> service.setActive(owner.userId(), false, manager)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("last_active_owner");
		assertThatThrownBy(() -> service.changeRole(owner.userId(), StaffRole.MANAGER, manager)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("last_active_owner");
		assertThat(repo.members.get(owner.userId()).role()).isEqualTo(StaffRole.OWNER);
		// a second owner makes both allowed
		service.changeRole(manager.userId(), StaffRole.OWNER, owner);
		var demoted = service.changeRole(owner.userId(), StaffRole.STAFF, manager);
		assertThat(demoted.role()).isEqualTo(StaffRole.STAFF);
		// removing a non-owner never needs an owner check
		service.setActive(owner.userId(), false, manager);
		assertThat(repo.members.get(owner.userId()).active()).isFalse();
	}

	@Test
	void changingARoleIsAuditedNobodyChangesTheirOwnAndAnUnknownMemberIsNotFound() {
		var person = UUID.randomUUID();
		service.accept(service.invite("p", StaffRole.STAFF, owner).code(), Actor.staff(person));
		var changed = service.changeRole(person, StaffRole.MANAGER, owner);
		assertThat(changed.role()).isEqualTo(StaffRole.MANAGER);
		assertThat(changed.updatedBy()).isEqualTo(owner.userId());
		var event = audit.stream().filter(e -> e.action().equals("STAFF_ROLE_CHANGED")).findFirst().orElseThrow();
		assertThat(event.toString()).contains("STAFF").contains("MANAGER");
		assertThatThrownBy(() -> service.changeRole(owner.userId(), StaffRole.STAFF, owner)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("cannot_change_own_access");
		assertThatThrownBy(() -> service.changeRole(UUID.randomUUID(), StaffRole.STAFF, owner)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.changeRole(person, null, owner)).isInstanceOf(FieldValidationException.class);
		assertThat(service.changeRole(person, StaffRole.MANAGER, owner)).isEqualTo(changed);   // same role: nothing changes, nothing audited twice
		assertThat(audit.stream().filter(e -> e.action().equals("STAFF_ROLE_CHANGED"))).hasSize(1);
	}
}
