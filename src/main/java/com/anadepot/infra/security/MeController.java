package com.anadepot.infra.security;

import com.anadepot.infra.customer.CustomerJpaRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who am I, according to the BACKEND. Identity (id, e-mail) comes from the verified token; {@code staff} comes from our
 * own staff table. The frontend calls this to decide what to show, but every protected endpoint re-checks on the server.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

	record MeResponse(UUID userId, String email, boolean emailVerified, boolean staff, boolean mfaRequired, String role, List<String> permissions, UUID customerProfileId) {
	}

	private final CustomerJpaRepository customers;

	MeController(CustomerJpaRepository customers) {
		this.customers = customers;
	}

	@GetMapping
	MeResponse me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
		var userId = UUID.fromString(jwt.getSubject());
		boolean staff = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(StaffAuthoritiesConverter.STAFF));
		// an active member whose session lacks the second factor: not staff yet, and the web sends them to enroll/verify
		boolean mfaRequired = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(StaffAuthoritiesConverter.MFA_REQUIRED));
		var profile = customers.findByAuthUserId(userId).map(c -> c.id()).orElse(null);
		// role and permissions come from OUR staff table (via the authorities the converter derived); the web only uses them to decide what to SHOW, every endpoint re-checks
		var role = authorities(authentication).stream().filter(a -> a.startsWith(StaffAuthoritiesConverter.ROLE_PREFIX)).map(a -> a.substring(StaffAuthoritiesConverter.ROLE_PREFIX.length())).findFirst().orElse(null);
		var permissions = authorities(authentication).stream().filter(a -> a.startsWith("PERM_")).map(a -> a.substring(5)).sorted().toList();
		return new MeResponse(userId, jwt.getClaimAsString("email"), emailVerified(jwt), staff, mfaRequired, role, permissions, profile);
	}

	private static List<String> authorities(Authentication authentication) {
		return authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
	}

	/** Informational only (never used for authorization): Supabase issues sessions to verified addresses when confirmation is enabled. */
	private static boolean emailVerified(Jwt jwt) {
		var top = jwt.getClaim("email_verified");
		if (top instanceof Boolean b) {
			return b;
		}
		var meta = jwt.getClaim("user_metadata");
		return meta instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("email_verified"));
	}
}
