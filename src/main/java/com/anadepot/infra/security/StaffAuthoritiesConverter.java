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
 */
@Component
public class StaffAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	public static final String AUTHENTICATED = "AUTHENTICATED";
	public static final String STAFF = "STAFF";

	private final StaffDirectory staff;

	StaffAuthoritiesConverter(StaffDirectory staff) {
		this.staff = staff;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		var authorities = new ArrayList<SimpleGrantedAuthority>();
		authorities.add(new SimpleGrantedAuthority(AUTHENTICATED));
		if (staff.isActiveStaff(UUID.fromString(jwt.getSubject()))) {
			authorities.add(new SimpleGrantedAuthority(STAFF));
		}
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}
}
