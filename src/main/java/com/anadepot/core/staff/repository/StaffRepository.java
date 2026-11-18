package com.anadepot.core.staff.repository;

import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for staff membership and invitations. */
public interface StaffRepository {

	List<StaffMember> findAllMembers();

	Optional<StaffMember> findMember(UUID userId);

	/** Every ACTIVE member, locked until the transaction ends: two people removing each other at once can never leave the store without staff. */
	List<StaffMember> lockActiveMembers();

	StaffMember saveMember(StaffMember member);

	List<StaffInvitation> findInvitations();

	Optional<StaffInvitation> findInvitation(UUID id);

	/** The invitation with this code hash, locked: two people redeeming one code at once, only one wins. */
	Optional<StaffInvitation> lockInvitationByHash(String codeHash);

	StaffInvitation saveInvitation(StaffInvitation invitation);
}
