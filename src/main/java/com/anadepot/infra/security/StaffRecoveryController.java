package com.anadepot.infra.security;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.ActorKind;
import com.anadepot.core.staff.service.StaffRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A team member who lost their authenticator redeems one recovery code. Reachable with a password-only session (that is the point), so it is outside /admin; the account is
 * ALWAYS the verified token's subject and the grant is tied to the token's session id. Strictly rate limited ({@code staff.recovery}).
 */
@RestController
@RequestMapping("/api/v1/me/staff-recovery")
class StaffRecoveryController {

	record RedeemRequest(@NotBlank @Size(max = 64) String code) {

		@Override
		public String toString() {
			return "RedeemRequest[code=hidden]";
		}
	}

	record Recovered(boolean recovered, Instant expiresAt, int remaining) {
	}

	private final StaffRecoveryService recovery;

	StaffRecoveryController(StaffRecoveryService recovery) {
		this.recovery = recovery;
	}

	@PostMapping("/redeem")
	Recovered redeem(@Valid @RequestBody RedeemRequest body, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
		var grant = recovery.redeem(body.code(), jwt.getClaimAsString("session_id"), new Actor(ActorKind.STAFF, UUID.fromString(authentication.getName())));
		return new Recovered(true, grant.expiresAt(), grant.remaining());
	}
}
