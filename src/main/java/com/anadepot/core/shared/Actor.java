package com.anadepot.core.shared;

import java.util.UUID;

/**
 * Who performed an action. {@code userId} is the identity-provider subject (Supabase Auth user id) for STAFF and for
 * signed-in customers, and null for guests and for the system. Roles and permissions are NOT modelled: they are an
 * open business decision, so core never asks "is this actor allowed".
 */
public record Actor(ActorKind kind, UUID userId) {

	public static Actor guest() {
		return new Actor(ActorKind.CUSTOMER, null);
	}

	public static Actor staff(UUID userId) {
		return new Actor(ActorKind.STAFF, userId);
	}

	public static Actor system() {
		return new Actor(ActorKind.SYSTEM, null);
	}
}
