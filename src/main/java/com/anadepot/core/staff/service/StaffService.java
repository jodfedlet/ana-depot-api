package com.anadepot.core.staff.service;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.staff.model.IssuedInvitation;
import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import com.anadepot.core.staff.model.StaffRole;
import java.util.List;
import java.util.UUID;

/**
 * Who works for the store, with one of three fixed roles (D14). A member is invited with a one-time code that carries the role to grant; the invitee redeems it while
 * signed in with their own account. Rules: nobody can change their own access or role, and the last active OWNER can never be removed or demoted.
 */
public interface StaffService {

	List<StaffMember> members();

	List<StaffInvitation> invitations();

	IssuedInvitation invite(String note, StaffRole role, Actor by);

	void revokeInvitation(UUID invitationId, Actor by);

	/** Redeems a code for the signed-in {@code actor}. Every invalid code (unknown, used, revoked, expired) answers the same way. */
	StaffMember accept(String code, Actor actor);

	StaffMember setActive(UUID userId, boolean active, Actor by);

	/** Changes the role of an existing member (audited). */
	StaffMember changeRole(UUID userId, StaffRole role, Actor by);
}
