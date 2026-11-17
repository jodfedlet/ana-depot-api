package com.anadepot.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Operations endpoints on a PRIVATE management port, for a scraper that cannot hold a user token (a Supabase access token lives one hour).
 *
 * Only active when {@code management.server.port} is set (and differs from the API port): then {@code /actuator/health/**}, {@code /actuator/metrics/**} and
 * {@code /actuator/prometheus} answer without a token on THAT port and nothing else does. The port must never be reachable from the internet
 * (bind it to a private interface/network, see docs/operations.md): its protection is the network, so this chain only matches requests that arrive on it.
 * The public API port keeps {@code SecurityConfig}: health probes are public, metrics are staff-only, {@code /actuator/prometheus} is denied.
 */
@Configuration
@ConditionalOnProperty("management.server.port")
class ManagementSecurityConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain managementSecurity(HttpSecurity http, Environment environment) throws Exception {
		http
				.securityMatcher(request -> {
					var managementPort = environment.getProperty("local.management.port"); // the port actually bound (also when configured as 0)
					return managementPort != null && request.getLocalPort() == Integer.parseInt(managementPort);
				})
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus").permitAll()
						.anyRequest().denyAll());
		return http.build();
	}
}
