package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0016 as a convention check on the source files (no Docker needed): the application stays on standard PostgreSQL and no hosting provider leaks into
 * business code, migrations or shared configuration. Comments are ignored; V11 is the one released migration that deliberately names provider roles (it only
 * touches them when they exist, and DataApiLockdownIntegrationTest proves it on plain PostgreSQL).
 */
class ProviderIndependenceConventionTest {

	private static final Path MAIN = Path.of("src/main");
	private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
	/** Roles and objects that exist only on some hosting providers. */
	private static final Pattern PROVIDER_ROLE = Pattern.compile("(?i)\\b(anon|authenticated|service_role|authenticator|supabase_[a-z_]+|azure_[a-z_]+|rds_[a-z_]+|rdsadmin|pgbouncer|pg_net|pgsodium)\\b");
	private static final Pattern PROVIDER_HOST = Pattern.compile("(?i)(supabase\\.(co|com|net)|pooler\\.supabase|rds\\.amazonaws\\.com|\\.postgres\\.database\\.azure\\.com|database\\.windows\\.net)");

	private static String withoutSqlComments(String sql) {
		return sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
	}

	private static List<Path> files(Path root, String suffix) throws IOException {
		try (Stream<Path> all = Files.walk(root)) {
			return all.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
		}
	}

	@Test
	void migrationsAfterTheLockdownUseOnlyStandardPostgreSql() throws IOException {
		var offences = new ArrayList<String>();
		for (var file : files(MAIN.resolve("resources/db/migration"), ".sql")) {
			var matcher = VERSION.matcher(file.getFileName().toString());
			assertThat(matcher.matches()).as(file.toString()).isTrue();
			var sql = withoutSqlComments(Files.readString(file, StandardCharsets.UTF_8));
			if (Integer.parseInt(matcher.group(1)) != 11) {
				var role = PROVIDER_ROLE.matcher(sql);
				while (role.find()) {
					offences.add(file.getFileName() + ": provider-specific name '" + role.group() + "'");
				}
			}
			if (Integer.parseInt(matcher.group(1)) > 11) {
				// New migrations are schema + data only: no extensions, roles, grants, definer functions or tablespaces (ADR 0016 rule M3). An exception needs a new ADR.
				var forbidden = Pattern.compile("(?is)\\b(create\\s+extension|create\\s+role|alter\\s+role|create\\s+user|grant\\s|revoke\\s|security\\s+definer|tablespace|set\\s+role|copy\\s+.*\\s+program)\\b").matcher(sql);
				while (forbidden.find()) {
					offences.add(file.getFileName() + ": '" + forbidden.group().strip() + "' is not allowed in a migration after V11");
				}
			}
		}
		assertThat(offences).isEmpty();
	}

	@Test
	void noProviderHostnameIsHardcodedInTheApplicationConfiguration() throws IOException {
		var offences = new ArrayList<String>();
		for (var file : files(MAIN.resolve("resources"), ".yaml")) {
			var yaml = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)\\s#.*$|^#.*$", "");
			var host = PROVIDER_HOST.matcher(yaml);
			while (host.find()) {
				offences.add(file.getFileName() + ": " + host.group());
			}
		}
		assertThat(offences).as("the database URL, like every credential, comes from SPRING_DATASOURCE_* only").isEmpty();
	}

	@Test
	void businessCodeAndPersistenceNeverMentionAHostingProviderOrTouchTheDriverOrDataSource() throws IOException {
		var offences = new ArrayList<String>();
		var providerWords = Pattern.compile("(?i)\\b(azure|rds|service_role)\\b|amazonaws");
		var connectionInfrastructure = Pattern.compile("(?i)\\b(supavisor|pgbouncer|pooler)\\b");   // only the startup guard (infra) may talk about poolers: it is the operational boundary
		var driver = Pattern.compile("org\\.postgresql\\.|javax\\.sql\\.|jakarta\\.sql\\.|\\bDataSource\\b");
		for (var file : files(MAIN.resolve("java"), ".java")) {
			var source = Files.readString(file, StandardCharsets.UTF_8);
			var code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
			var inCore = file.toString().contains("/com/anadepot/core/");
			var word = providerWords.matcher(inCore ? source : code);
			if (word.find()) {
				offences.add(file + ": provider-specific word '" + word.group() + "'");
			}
			var pooler = connectionInfrastructure.matcher(source);
			if (inCore && pooler.find()) {
				offences.add(file + ": connection infrastructure '" + pooler.group() + "' in core");
			}
			var jdbc = driver.matcher(code);
			if (jdbc.find()) {
				offences.add(file + ": direct driver/DataSource use '" + jdbc.group() + "' (persistence is JPA only)");
			}
		}
		assertThat(offences).isEmpty();
	}
}
