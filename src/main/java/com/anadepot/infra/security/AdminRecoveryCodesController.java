package com.anadepot.infra.security;

import com.anadepot.core.staff.service.StaffRecoveryService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in member's OWN recovery codes: make a new set (shown once) and see how many are left. Never another person's. */
@RestController
@RequestMapping("/api/v1/admin/me/recovery-codes")
class AdminRecoveryCodesController {

	record IssuedView(List<String> codes, Instant generatedAt, int remaining) {

		@Override
		public String toString() {
			return "IssuedView[codes=hidden]";
		}
	}

	record StatusView(int remaining, Instant generatedAt) {
	}

	private final StaffRecoveryService recovery;

	AdminRecoveryCodesController(StaffRecoveryService recovery) {
		this.recovery = recovery;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	IssuedView generate(Authentication authentication) {
		var issued = recovery.generate(Actors.staff(authentication));
		return new IssuedView(issued.codes(), issued.generatedAt(), issued.codes().size());
	}

	@GetMapping
	StatusView status(Authentication authentication) {
		var s = recovery.status(java.util.UUID.fromString(authentication.getName()));
		return new StatusView(s.remaining(), s.generatedAt());
	}
}
