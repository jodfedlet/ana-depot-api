package com.anadepot.infra.security;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.ActorKind;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/** Derives the acting person from the VERIFIED authentication (never from request bodies or headers). */
public final class Actors {

	private Actors() {
	}

	public static Actor staff(Authentication authentication) {
		return new Actor(ActorKind.STAFF, UUID.fromString(authentication.getName()));
	}

	/** The signed-in customer, or the anonymous guest when there is no (valid) token. */
	public static Actor customerOrGuest(Authentication authentication) {
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
			return Actor.guest();
		}
		return new Actor(ActorKind.CUSTOMER, UUID.fromString(authentication.getName()));
	}
}
