package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * V11 enabled Row Level Security on the tables that existed then. Every table created by a LATER migration must enable it in the same file, otherwise it would be
 * the one table of the schema that the Supabase Data API could serve (docs/supabase-database.md, ADR 0013). A convention check on the migration files themselves.
 */
class MigrationRlsConventionTest {

	private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
	private static final Pattern CREATE = Pattern.compile("(?im)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:public\\.)?\"?([a-z0-9_]+)\"?");

	@Test
	void everyTableCreatedAfterTheLockdownMigrationEnablesRowLevelSecurity() throws IOException {
		var missing = new ArrayList<String>();
		for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql")) {
			var name = resource.getFilename();
			var matcher = VERSION.matcher(name);
			assertThat(matcher.matches()).as(name).isTrue();
			if (Integer.parseInt(matcher.group(1)) <= 11) {
				continue;
			}
			var sql = resource.getContentAsString(StandardCharsets.UTF_8);
			var tables = CREATE.matcher(sql);
			while (tables.find()) {
				var table = tables.group(1);
				var enable = Pattern.compile("(?is)alter\\s+table\\s+(?:public\\.)?\"?" + table + "\"?\\s+enable\\s+row\\s+level\\s+security");
				if (!enable.matcher(sql).find()) {
					missing.add(name + ": " + table);
				}
			}
		}
		assertThat(missing).as("tables created after V11 without `alter table ... enable row level security`").isEmpty();
	}
}
