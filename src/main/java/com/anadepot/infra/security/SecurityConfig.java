package com.anadepot.infra.security;

import com.anadepot.infra.config.AnaProperties;
import com.anadepot.infra.ratelimit.ClientIpResolver;
import com.anadepot.infra.ratelimit.RateLimitFilter;
import com.anadepot.infra.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security. DENY BY DEFAULT: anything not listed is refused. Authorization is decided here, on the server;
 * the frontend's hidden links mean nothing. Public: the health probe and the read-only catalog. Authenticated: /me.
 * Staff: /api/v1/admin/**. Reservation endpoints for guests (no account required) will be added with their use cases.
 */
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, StaffAuthoritiesConverter authorities, ProblemSecurityHandlers handlers,
			RateLimiter limiter, AnaProperties properties) throws Exception {
		http
				.csrf(csrf -> csrf.disable()) // stateless bearer-token API: no cookies, nothing for CSRF to ride on
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers("/actuator/metrics/**").hasAuthority(StaffAuthoritiesConverter.STAFF)
						.requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/store/**").permitAll()
						// guests reserve and track WITHOUT an account (a bearer token, if present, links the reservation to that customer)
						.requestMatchers(HttpMethod.POST, "/api/v1/reservations", "/api/v1/reservations/lookup").permitAll()
						.requestMatchers("/api/v1/me/**").hasAuthority(StaffAuthoritiesConverter.AUTHENTICATED)
						.requestMatchers("/api/v1/admin/**").hasAuthority(StaffAuthoritiesConverter.STAFF)
						.anyRequest().denyAll())
				.oauth2ResourceServer(o -> o
						.jwt(j -> j.jwtAuthenticationConverter(authorities))
						.authenticationEntryPoint(handlers)
						.accessDeniedHandler(handlers))
				.exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
				.addFilterAfter(new RateLimitFilter(limiter, new ClientIpResolver(properties.rateLimit().trustedProxies())), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	/** Fail closed: with no issuer configured, every token is rejected (public catalog reads still work). */
	@Bean
	JwtDecoder jwtDecoder(AnaProperties properties) {
		var security = properties.security();
		if (security.jwtIssuer() == null || security.jwtIssuer().isBlank()) {
			return token -> {
				throw new BadJwtException("Authentication is not configured");
			};
		}
		return SupabaseJwtDecoders.fromIssuer(security.jwtIssuer(), security.jwtAudience());
	}
}
