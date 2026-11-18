package com.anadepot.infra.security;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.ActorKind;
import com.anadepot.core.staff.service.StaffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A signed-in person redeems the one-time code they were given. The acting account is ALWAYS the verified token's subject. */
@RestController
@RequestMapping("/api/v1/me/staff-invitation")
class StaffInvitationController {

	record RedeemRequest(@NotBlank @Size(max = 64) String code) {

		@Override
		public String toString() {
			return "RedeemRequest[code=hidden]";
		}
	}

	record Joined(boolean joined) {
	}

	private final StaffService staff;

	StaffInvitationController(StaffService staff) {
		this.staff = staff;
	}

	@PostMapping
	Joined redeem(@Valid @RequestBody RedeemRequest body, Authentication authentication) {
		staff.accept(body.code(), new Actor(ActorKind.STAFF, UUID.fromString(authentication.getName())));
		return new Joined(true);
	}
}
