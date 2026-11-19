package com.anadepot.core.staff.service;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.staff.model.RecoveryCodes;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Second-factor recovery codes (D15). A member makes a set while signed in with their second factor; if the authenticator is lost, one code lets that sign-in session use
 * the administration for a short time, and every use is audited. Generating a new set replaces the old unused codes.
 */
public interface StaffRecoveryService {

	/** Ten new codes, shown once; the member's previous unused codes stop working. */
	RecoveryCodes.Issued generate(Actor actor);

	RecoveryCodes.Status status(UUID userId);

	/**
	 * Redeems one code for the sign-in session {@code sessionId} of {@code actor}. Every invalid code (unknown, used, replaced, someone else's, not a team member) answers the same way.
	 */
	RecoveryCodes.Grant redeem(String code, String sessionId, Actor actor);

	/** True while a code redeemed by THIS session is still within its time. */
	boolean isRecoveredSession(UUID userId, String sessionId);

	/** Unused codes per member (owner's readiness view of who has recovery codes). */
	Map<UUID, Integer> remaining(Collection<UUID> userIds);
}
