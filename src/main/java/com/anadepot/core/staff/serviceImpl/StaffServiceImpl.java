package com.anadepot.core.staff.serviceImpl;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.staff.model.IssuedInvitation;
import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.model.StaffRole;
import com.anadepot.core.staff.repository.StaffRepository;
import com.anadepot.core.staff.service.StaffService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class StaffServiceImpl implements StaffService {

	public static final int NOTE_MAX = 80;
	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no 0/O/1/I/L: a code read aloud or copied by hand stays unambiguous
	private static final int CODE_LENGTH = 24;                              // 30^24 ≈ 2^117 possibilities: not guessable even without a rate limit

	private final StaffRepository staff;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final Duration invitationLifetime;
	private final SecureRandom random = new SecureRandom();

	public StaffServiceImpl(StaffRepository staff, AuditRecorder audit, TransactionBoundary tx, Clock clock, Duration invitationLifetime) {
		this.staff = staff;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
		this.invitationLifetime = invitationLifetime;
	}

	@Override
	public List<StaffMember> members() {
		return tx.inTransaction(staff::findAllMembers);
	}

	@Override
	public List<StaffInvitation> invitations() {
		return tx.inTransaction(staff::findInvitations);
	}

	@Override
	public IssuedInvitation invite(String note, StaffRole role, Actor by) {
		if (role == null) {
			throw new FieldValidationException(List.of(new FieldValidationException.FieldIssue("role", "required")));
		}
		var clean = note == null ? "" : note.strip();
		if (clean.isEmpty() || clean.length() > NOTE_MAX) {
			throw new FieldValidationException(List.of(new FieldValidationException.FieldIssue("note", clean.isEmpty() ? "required" : "too_long")));
		}
		var code = newCode();
		var now = clock.instant();
		var invitation = new StaffInvitation(UUID.randomUUID(), hash(code), clean, role, by.userId(), now, now.plus(invitationLifetime), null, null, null, null);
		return tx.inTransaction(() -> {
			var saved = staff.saveInvitation(invitation);
			audit.record(AuditEvent.of("STAFF_INVITED", "staff_invitation", saved.id(), by, Map.of(), Map.of("expiresAt", saved.expiresAt().toString(), "noteLength", clean.length(), "role", role.name()), null));
			return new IssuedInvitation(saved, display(code));
		});
	}

	@Override
	public void revokeInvitation(UUID invitationId, Actor by) {
		tx.inTransaction(() -> {
			var invitation = staff.findInvitation(invitationId).orElseThrow(() -> new NotFoundException("Invitation", invitationId));
			if (invitation.status(clock.instant()) != StaffInvitation.Status.PENDING) {
				throw new DomainRuleViolation("invitation_not_pending", "Only a pending invitation can be revoked.");
			}
			staff.saveInvitation(invitation.revoked(by.userId(), clock.instant()));
			audit.record(AuditEvent.of("STAFF_INVITATION_REVOKED", "staff_invitation", invitationId, by, Map.of(), Map.of(), null));
		});
	}

	@Override
	public StaffMember accept(String code, Actor actor) {
		if (actor == null || actor.userId() == null) {
			throw invalid();
		}
		var normalized = normalize(code);
		if (normalized.length() != CODE_LENGTH) {
			throw invalid();
		}
		return tx.inTransaction(() -> {
			var invitation = staff.lockInvitationByHash(hash(normalized)).orElseThrow(StaffServiceImpl::invalid);
			var now = clock.instant();
			if (invitation.status(now) != StaffInvitation.Status.PENDING) {
				throw invalid();
			}
			var existing = staff.findMember(actor.userId());
			if (existing.isPresent() && existing.get().active()) {
				throw new DomainRuleViolation("already_staff", "This account already belongs to the team.");
			}
			staff.saveInvitation(invitation.accepted(actor.userId(), now));
			var member = existing.map(m -> m.rejoined(invitation.role(), actor.userId(), now))
					.orElseGet(() -> new StaffMember(actor.userId(), true, invitation.role(), invitation.createdBy(), now, invitation.note(), null, null));
			var saved = staff.saveMember(member);
			audit.record(AuditEvent.of("STAFF_JOINED", "staff_member", actor.userId(), actor, Map.of("active", existing.map(StaffMember::active).orElse(false)), Map.of("active", true, "role", invitation.role().name()),
					"invitation " + invitation.id()));
			return saved;
		});
	}

	@Override
	public StaffMember setActive(UUID userId, boolean active, Actor by) {
		if (userId.equals(by.userId())) {
			throw new DomainRuleViolation("cannot_change_own_access", "You cannot change your own access. Ask a colleague.");
		}
		return tx.inTransaction(() -> {
			var activeNow = staff.lockActiveMembers();
			var member = staff.findMember(userId).orElseThrow(() -> new NotFoundException("Staff member", userId));
			if (member.active() == active) {
				return member;
			}
			if (!active && member.role() == StaffRole.OWNER && activeNow.stream().noneMatch(m -> m.role() == StaffRole.OWNER && !m.userId().equals(userId))) {
				throw new DomainRuleViolation("last_active_owner", "The last active owner cannot be removed.");
			}
			var saved = staff.saveMember(member.withActive(active, by.userId(), clock.instant()));
			audit.record(AuditEvent.of(active ? "STAFF_REACTIVATED" : "STAFF_DEACTIVATED", "staff_member", userId, by, Map.of("active", !active), Map.of("active", active), null));
			return saved;
		});
	}

	@Override
	public StaffMember changeRole(UUID userId, StaffRole role, Actor by) {
		if (role == null) {
			throw new FieldValidationException(List.of(new FieldValidationException.FieldIssue("role", "required")));
		}
		if (userId.equals(by.userId())) {
			throw new DomainRuleViolation("cannot_change_own_access", "You cannot change your own access. Ask a colleague.");
		}
		return tx.inTransaction(() -> {
			var activeNow = staff.lockActiveMembers();
			var member = staff.findMember(userId).orElseThrow(() -> new NotFoundException("Staff member", userId));
			if (member.role() == role) {
				return member;
			}
			if (member.active() && member.role() == StaffRole.OWNER && activeNow.stream().noneMatch(m -> m.role() == StaffRole.OWNER && !m.userId().equals(userId))) {
				throw new DomainRuleViolation("last_active_owner", "The last active owner cannot be demoted.");
			}
			var saved = staff.saveMember(member.withRole(role, by.userId(), clock.instant()));
			audit.record(AuditEvent.of("STAFF_ROLE_CHANGED", "staff_member", userId, by, Map.of("role", member.role().name()), Map.of("role", role.name()), null));
			return saved;
		});
	}

	private String newCode() {
		var sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	/** Groups of four for reading and typing: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX. */
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

	private static String hash(String normalizedCode) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static NotFoundException invalid() {
		return NotFoundException.withCode("invitation_invalid", "This invitation code is not valid. It may have expired or already been used.");
	}
}
