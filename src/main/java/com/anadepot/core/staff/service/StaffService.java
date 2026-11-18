package com.anadepot.core.staff.service;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.staff.model.IssuedInvitation;
import com.anadepot.core.staff.model.StaffInvitation;
import com.anadepot.core.staff.model.StaffMember;
import java.util.List;
import java.util.UUID;

/**
 * Who works for the store. All-or-nothing membership (roles are an open business decision). A member is invited with a one-time code; the invitee redeems it while
 * signed in with their own account. Rules: nobody can change their own access, and the last active member can never be removed.
 */
public interface StaffService {

	List<StaffMember> members();

	List<StaffInvitation> invitations();

	IssuedInvitation invite(String note, Actor by);

	void revokeInvitation(UUID invitationId, Actor by);

	/** Redeems a code for the signed-in {@code actor}. Every invalid code (unknown, used, revoked, expired) answers the same way. */
	StaffMember accept(String code, Actor actor);

	StaffMember setActive(UUID userId, boolean active, Actor by);
}
