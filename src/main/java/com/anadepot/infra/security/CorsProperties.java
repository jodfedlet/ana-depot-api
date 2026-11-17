package com.anadepot.infra.security;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins allowed to call the API directly ({@code ANA_CORS_ALLOWED_ORIGINS}, comma separated, exact origins such as
 * {@code https://www.example.com}). Empty (the default) means no cross-origin browser call is allowed, which is the safe state.
 * A wildcard is refused on purpose: the API is credentialed by bearer tokens and must name who may call it from a browser.
 */
@ConfigurationProperties(prefix = "ana.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().filter(Objects::nonNull).map(String::strip).filter(o -> !o.isEmpty()).toList();
		for (var origin : allowedOrigins) {
			if (origin.contains("*")) {
				throw new IllegalStateException("ana.cors.allowed-origins must list exact origins; wildcards are not allowed (found '" + origin + "')");
			}
		}
	}
}
