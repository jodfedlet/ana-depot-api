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
		for (var warning : roleWarnings(environment.getProperty("spring.datasource.username"), environment.getProperty("spring.flyway.user"))) {
			log.warn("Production configuration: {}", warning);
		}
		if (!Boolean.parseBoolean(environment.getProperty("ana.staff.require-mfa", "false"))) {
			log.error("Production configuration: staff second-factor enforcement is OFF (ANA_STAFF_REQUIRE_MFA=false). Anyone with a staff password can use the administration. Turn it on once every member has enrolled (docs/auth.md).");
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
		var r2 = properties.storage() == null ? null : properties.storage().r2();
		if (r2 != null && r2.anySet() && !r2.complete()) {
			problems.add("Cloudflare R2 is only partly configured, missing " + r2.missing());
		}
		return problems;
	}

	static List<String> warnings(String url, AnaProperties properties) {
		var warnings = new ArrayList<String>();
		if (url != null && !url.contains("sslmode=") && !url.contains("ssl=true")) {
			warnings.add("the database URL has no sslmode: add sslmode=require, or sslmode=verify-full with the provider's CA certificate (hosted PostgreSQL services accept only TLS from outside their network; docs/database-providers.md)");
		}
		if (url != null && (url.contains(":6543") || url.contains(":6432")) && !url.contains("prepareThreshold=0")) {
			warnings.add("port 6543/6432 looks like a transaction pooler (PgBouncer, Supavisor, a managed built-in pooler): add prepareThreshold=0 and run Flyway on a direct or session connection (docs/database-providers.md)");
		}
		var r2 = properties.storage() == null ? null : properties.storage().r2();
		if (r2 == null || !r2.anySet()) {
			warnings.add("Cloudflare R2 is not configured: product image uploads are disabled (storage_not_configured)");
		}
		if (properties.rateLimit().trustedProxies() == null || properties.rateLimit().trustedProxies().isEmpty()) {
			warnings.add("ana.rate-limit.trusted-proxies is empty: every client is rate limited by the address of the proxy if the API runs behind one");
		}
		return warnings;
	}

	/**
	 * V11 enables Row Level Security without policies, so the role that RUNS the application must own the tables (the role that ran Flyway) or have BYPASSRLS; otherwise every
	 * table looks empty. The one configuration that can break that on any provider is a Flyway role different from the application role.
	 */
	static List<String> roleWarnings(String datasourceUsername, String flywayUsername) {
		var warnings = new ArrayList<String>();
		if (flywayUsername != null && !flywayUsername.isBlank() && !flywayUsername.equals(datasourceUsername)) {
			warnings.add("Flyway runs as a different role than the application: the application role must OWN the tables or have BYPASSRLS, otherwise Row Level Security (V11, no policies) makes every table look empty (docs/database-providers.md)");
		}
		return warnings;
	}
}
