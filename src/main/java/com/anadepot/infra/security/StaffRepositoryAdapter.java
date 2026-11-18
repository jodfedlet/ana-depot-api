package com.anadepot.infra.security;

import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.repository.StaffRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class StaffRepositoryAdapter implements StaffRepository {

	private final StaffMemberJpaRepository members;
	private final StaffInvitationJpaRepository invitations;

	StaffRepositoryAdapter(StaffMemberJpaRepository members, StaffInvitationJpaRepository invitations) {
		this.members = members;
		this.invitations = invitations;
	}

	@Override
	public List<StaffMember> findAllMembers() {
		return members.findAllByOrderByGrantedAtAsc().stream().map(StaffRepositoryAdapter::toDomain).toList();
	}

	@Override
	public Optional<StaffMember> findMember(UUID userId) {
		return members.findById(userId).map(StaffRepositoryAdapter::toDomain);
	}

	@Override
	public List<StaffMember> lockActiveMembers() {
		return members.lockActive().stream().map(StaffRepositoryAdapter::toDomain).toList();
	}

	@Override
	public StaffMember saveMember(StaffMember member) {
		var e = members.findById(member.userId()).orElseGet(StaffMemberEntity::new);
		e.userId = member.userId();
		e.active = member.active();
		e.grantedBy = member.grantedBy();
		e.grantedAt = member.grantedAt();
		e.note = member.note();
		e.updatedBy = member.updatedBy();
		e.updatedAt = member.updatedAt();
		return toDomain(members.saveAndFlush(e));
	}

	@Override
	public List<StaffInvitation> findInvitations() {
		return invitations.findAllByOrderByCreatedAtDesc().stream().map(StaffRepositoryAdapter::toDomain).toList();
	}

	@Override
	public Optional<StaffInvitation> findInvitation(UUID id) {
		return invitations.findById(id).map(StaffRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<StaffInvitation> lockInvitationByHash(String codeHash) {
		return invitations.lockByCodeHash(codeHash).map(StaffRepositoryAdapter::toDomain);
	}

	@Override
	public StaffInvitation saveInvitation(StaffInvitation i) {
		var e = invitations.findById(i.id()).orElseGet(StaffInvitationEntity::new);
		e.id = i.id();
		e.codeHash = i.codeHash();
		e.note = i.note();
		e.createdBy = i.createdBy();
		e.createdAt = i.createdAt();
		e.expiresAt = i.expiresAt();
		e.acceptedBy = i.acceptedBy();
		e.acceptedAt = i.acceptedAt();
		e.revokedAt = i.revokedAt();
		e.revokedBy = i.revokedBy();
		return toDomain(invitations.saveAndFlush(e));
	}

	private static StaffMember toDomain(StaffMemberEntity e) {
		return new StaffMember(e.userId, e.active, e.grantedBy, e.grantedAt, e.note, e.updatedBy, e.updatedAt);
	}

	private static StaffInvitation toDomain(StaffInvitationEntity e) {
		return new StaffInvitation(e.id, e.codeHash, e.note, e.createdBy, e.createdAt, e.expiresAt, e.acceptedBy, e.acceptedAt, e.revokedAt, e.revokedBy);
	}
}
