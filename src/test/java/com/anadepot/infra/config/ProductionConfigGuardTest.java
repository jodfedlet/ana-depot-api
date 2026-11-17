package com.anadepot.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionConfigGuardTest {

	private static AnaProperties props(String supabaseUrl, String issuer, List<String> proxies) {
		return new AnaProperties(null, null, new AnaProperties.RateLimit(true, proxies, null, null, null),
				new AnaProperties.Security(supabaseUrl, issuer, null, "authenticated", null), null, null, null);
	}

	@Test
	void aCompleteConfigurationHasNoProblems() {
		assertThat(ProductionConfigGuard.problems("jdbc:postgresql://db.example:5432/postgres?sslmode=require", "ana", "secret", props(null, "https://x.supabase.co/auth/v1", List.of("10.0.0.0/8")))).isEmpty();
		assertThat(ProductionConfigGuard.problems("jdbc:postgresql://db.example:5432/postgres", "ana", "secret", props("https://x.supabase.co", null, List.of()))).isEmpty();
	}

	@Test
	void everyMissingPieceIsNamedAndNoValueIsEverIncluded() {
		var problems = ProductionConfigGuard.problems(null, "", null, props(null, null, null));
		assertThat(problems).hasSize(4);
		assertThat(String.join(" ", problems)).contains("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SUPABASE_JWT_ISSUER");
	}

	@Test
	void aHalfConfiguredR2IsAProblemButNoR2AtAllIsOnlyAWarning() {
		var half = new AnaProperties(null, null, new AnaProperties.RateLimit(true, List.of("10.0.0.1"), null, null, null), new AnaProperties.Security(null, "https://x/auth/v1", null, "authenticated", null), null,
				new AnaProperties.Storage(new AnaProperties.R2("acct", "bucket", null, null, null, null, true)), null);
		var problems = ProductionConfigGuard.problems("jdbc:postgresql://h/db", "a", "b", half);
		assertThat(problems).singleElement().asString().contains("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_PUBLIC_BASE_URL").doesNotContain("acct");
		assertThat(ProductionConfigGuard.warnings("jdbc:postgresql://h:5432/db?sslmode=require", props(null, "https://x/auth/v1", List.of("10.0.0.1")))).singleElement().asString().contains("R2 is not configured");
	}

	@Test
	void aNonPostgresUrlIsRefused() {
		assertThat(ProductionConfigGuard.problems("jdbc:mysql://x/db", "a", "b", props(null, "https://x/auth/v1", null))).singleElement().asString().contains("jdbc:postgresql");
	}

	@Test
	void warningsPointAtTlsThePoolerAndProxies() {
		var warnings = ProductionConfigGuard.warnings("jdbc:postgresql://pooler.example:6543/postgres", props(null, "https://x/auth/v1", List.of()));
		assertThat(warnings).hasSize(4);
		assertThat(ProductionConfigGuard.warnings("jdbc:postgresql://h:5432/postgres?sslmode=require", props(null, "https://x/auth/v1", List.of("10.0.0.1")))).singleElement().asString().contains("R2");
		assertThat(ProductionConfigGuard.warnings("jdbc:postgresql://h:6543/postgres?sslmode=require&prepareThreshold=0", props(null, "https://x/auth/v1", List.of("10.0.0.1")))).singleElement().asString().contains("R2");
	}
}
