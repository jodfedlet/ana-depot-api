package com.anadepot.infra.security;

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
 * person may do comes from OUR database. Everyone with a valid token gets {@code AUTHENTICATED}; {@code STAFF} only if
 * they are an active staff member. Claims such as {@code role}, {@code app_metadata} or {@code user_metadata} are ignored.
 *
 * With {@code ana.staff.require-mfa} an active staff member whose session has not completed a second factor ({@code aal} is not {@code aal2}; the claim is part of the signed token, not
 * user-editable metadata) gets {@code MFA_REQUIRED} instead of {@code STAFF}: they can sign in, enroll and verify, and nothing else.
 */
@Component
public class StaffAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	public static final String AUTHENTICATED = "AUTHENTICATED";
	public static final String STAFF = "STAFF";
	public static final String MFA_REQUIRED = "MFA_REQUIRED";

	private final StaffDirectory staff;
	private final StaffProperties properties;

	StaffAuthoritiesConverter(StaffDirectory staff, StaffProperties properties) {
		this.staff = staff;
		this.properties = properties;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		var authorities = new ArrayList<SimpleGrantedAuthority>();
		authorities.add(new SimpleGrantedAuthority(AUTHENTICATED));
		if (staff.isActiveStaff(UUID.fromString(jwt.getSubject()))) {
			authorities.add(new SimpleGrantedAuthority(properties.requireMfa() && !"aal2".equals(jwt.getClaimAsString("aal")) ? MFA_REQUIRED : STAFF));
		}
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}
}
