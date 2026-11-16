package com.anadepot.infra.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production fails fast and closed instead of starting half-configured: no database URL, no way to verify tokens, or a database URL that
 * is not PostgreSQL stops the start-up with ONE message naming what is missing (never a value). Warnings (not errors) for choices that are
 * allowed but worth a second look.
 */
@Component
@Profile("prod")
class ProductionConfigGuard {

	private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

	private final Environment environment;
	private final AnaProperties properties;

	ProductionConfigGuard(Environment environment, AnaProperties properties) {
		this.environment = environment;
		this.properties = properties;
	}

	@PostConstruct
	void check() {
		var url = environment.getProperty("spring.datasource.url");
		var problems = problems(url, environment.getProperty("spring.datasource.username"), environment.getProperty("spring.datasource.password"), properties);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Production configuration is incomplete: " + String.join("; ", problems));
		}
		for (var warning : warnings(url, properties)) {
			log.warn("Production configuration: {}", warning);
		}
	}

	static List<String> problems(String url, String username, String password, AnaProperties properties) {
		var problems = new ArrayList<String>();
		if (url == null || url.isBlank()) {
			problems.add("SPRING_DATASOURCE_URL is not set");
		} else if (!url.startsWith("jdbc:postgresql:")) {
			problems.add("SPRING_DATASOURCE_URL must be a jdbc:postgresql: URL");
		}
		if (username == null || username.isBlank()) {
			problems.add("SPRING_DATASOURCE_USERNAME is not set");
		}
		if (password == null || password.isBlank()) {
			problems.add("SPRING_DATASOURCE_PASSWORD is not set");
		}
		if (properties.security().effectiveIssuer() == null) {
			problems.add("neither SUPABASE_JWT_ISSUER nor SUPABASE_URL is set (every token would be rejected)");
		}
		return problems;
	}

	static List<String> warnings(String url, AnaProperties properties) {
		var warnings = new ArrayList<String>();
		if (url != null && !url.contains("sslmode=") && !url.contains("ssl=true")) {
			warnings.add("the database URL has no sslmode: add sslmode=require (Supabase accepts only TLS from outside its network)");
		}
		if (url != null && url.contains(":6543") && !url.contains("prepareThreshold=0")) {
			warnings.add("port 6543 looks like the transaction pooler: add prepareThreshold=0 and run Flyway on a direct or session connection (docs/supabase-database.md)");
		}
		if (properties.rateLimit().trustedProxies() == null || properties.rateLimit().trustedProxies().isEmpty()) {
			warnings.add("ana.rate-limit.trusted-proxies is empty: every client is rate limited by the address of the proxy if the API runs behind one");
		}
		return warnings;
	}
}
