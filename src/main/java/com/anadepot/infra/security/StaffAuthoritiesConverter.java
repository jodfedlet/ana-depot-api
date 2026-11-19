package com.anadepot.infra.security;

import com.anadepot.core.staff.model.Permission;
import com.anadepot.core.staff.service.StaffRecoveryService;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a VERIFIED token into the application's authentication: identity comes from the token ({@code sub}), what the
 * person may do comes from OUR database. Everyone with a valid token gets {@code AUTHENTICATED}; an active staff member also gets {@code STAFF}, {@code STAFF_ROLE_<role>} and one {@code PERM_<permission>} per
 * permission of their role (D14). Claims such as {@code role}, {@code app_metadata} or {@code user_metadata} are ignored.
 *
 * With {@code ana.staff.require-mfa} an active staff member whose session has not completed a second factor ({@code aal} is not {@code aal2}; the claim is part of the signed token, not
 * user-editable metadata) gets {@code MFA_REQUIRED} instead of {@code STAFF}: they can sign in, enroll, verify or redeem a recovery code, and nothing else. A session that redeemed a recovery code
 * (D15) is treated as having passed the requirement and carries {@code MFA_RECOVERED} until the code's time runs out.
 */
@Component
public class StaffAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	public static final String AUTHENTICATED = "AUTHENTICATED";
	public static final String STAFF = "STAFF";
	public static final String MFA_REQUIRED = "MFA_REQUIRED";
	/** The session passed the second-factor requirement through a recovery code, not through an authenticator. */
	public static final String MFA_RECOVERED = "MFA_RECOVERED";
	public static final String ROLE_PREFIX = "STAFF_ROLE_";

	/** The authority that carries one {@link Permission}; the HTTP rules ({@code AdminAccessRules}) require exactly these. */
	public static String permission(Permission p) {
		return "PERM_" + p.name();
	}

	private final StaffDirectory staff;
	private final StaffProperties properties;
	private final StaffRecoveryService recovery;

	StaffAuthoritiesConverter(StaffDirectory staff, StaffProperties properties, StaffRecoveryService recovery) {
		this.staff = staff;
		this.properties = properties;
		this.recovery = recovery;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		var authorities = new ArrayList<SimpleGrantedAuthority>();
		authorities.add(new SimpleGrantedAuthority(AUTHENTICATED));
		staff.activeRole(UUID.fromString(jwt.getSubject())).ifPresent(role -> {
			if (properties.requireMfa() && !"aal2".equals(jwt.getClaimAsString("aal"))) {
				// no second factor in this session: only a recovery code redeemed by THIS session (and still within its time) stands in for it
				if (!recovery.isRecoveredSession(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("session_id"))) {
					authorities.add(new SimpleGrantedAuthority(MFA_REQUIRED));
					return;
				}
				authorities.add(new SimpleGrantedAuthority(MFA_RECOVERED));
			}
			authorities.add(new SimpleGrantedAuthority(STAFF));
			authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role.name()));
			role.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(permission(p))));
		});
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}
}
