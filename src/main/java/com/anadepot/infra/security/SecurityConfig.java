package com.anadepot.infra.security;

import com.anadepot.infra.config.AnaProperties;
import com.anadepot.infra.ratelimit.ClientIpResolver;
import com.anadepot.infra.ratelimit.RateLimitFilter;
import com.anadepot.infra.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security. DENY BY DEFAULT: anything not listed is refused. Authorization is decided here, on the server;
 * the frontend's hidden links mean nothing. Public: the health probe and the read-only catalog. Authenticated: /me.
 * Staff: /api/v1/admin/**. Reservation endpoints for guests (no account required) will be added with their use cases.
 */
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, StaffAuthoritiesConverter authorities, ProblemSecurityHandlers handlers,
			RateLimiter limiter, AnaProperties properties, CorsProperties cors, HeaderProperties headerProperties) throws Exception {
		http
				.cors(c -> c.configurationSource(corsSource(cors)))
				.headers(h -> responseHeaders(h, headerProperties))
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

	/**
	 * Response headers of a JSON-only API. Spring Security already adds {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} and
	 * {@code Cache-Control: no-store} (so no private response is ever kept by a browser or shared cache); this adds a locked-down CSP (the API never serves
	 * a document), no referrer, a closed Permissions-Policy and, when the deployment is HTTPS-only, HSTS regardless of what the proxy forwards.
	 */
	static void responseHeaders(HeadersConfigurer<HttpSecurity> headers, HeaderProperties properties) {
		headers
				.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
				.permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()"))
				.crossOriginOpenerPolicy(c -> c.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
		if (properties.hsts()) {
			headers.httpStrictTransportSecurity(h -> h.requestMatcher(request -> true).maxAgeInSeconds(63_072_000).includeSubDomains(true));
		}
	}

	/**
	 * Cross-origin browser access for the storefront. Only the configured origins, only the methods and headers the API uses, no cookies
	 * (credentials stay off: authentication is the Authorization header). With no origin configured the source has no mapping and browsers are refused.
	 */
	static UrlBasedCorsConfigurationSource corsSource(CorsProperties cors) {
		var source = new UrlBasedCorsConfigurationSource();
		if (!cors.allowedOrigins().isEmpty()) {
			var config = new CorsConfiguration();
			config.setAllowedOrigins(cors.allowedOrigins());
			config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
			config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Correlation-Id"));
			config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
			config.setAllowCredentials(false);
			config.setMaxAge(Duration.ofHours(1));
			source.registerCorsConfiguration("/api/**", config);
		}
		return source;
	}

	/** Fail closed: with no issuer configured, every token is rejected (public catalog reads still work). */
	@Bean
	JwtDecoder jwtDecoder(AnaProperties properties) {
		var security = properties.security();
		var issuer = security.effectiveIssuer();
		if (issuer == null) {
			return token -> {
				throw new BadJwtException("Authentication is not configured");
			};
		}
		return SupabaseJwtDecoders.fromIssuer(issuer, security.jwtAudience(), security.jwksUri());
	}
}
