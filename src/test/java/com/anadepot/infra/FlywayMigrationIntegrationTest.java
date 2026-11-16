package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Flyway ran the production migrations on an empty PostgreSQL, in order, successfully. (Hibernate's {@code ddl-auto=validate}
 * additionally proves every entity matches the migrated schema: the context could not start otherwise.)
 */
class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

	@Autowired Flyway flyway;

	@Test
	void allMigrationsAreAppliedInOrderWithoutAnythingPending() {
		var all = flyway.info().all();
		assertThat(all).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
		assertThat(all).extracting(m -> m.getDescription()).containsExactly("catalog", "customers and inventory", "reservations", "payments and ledger", "platform", "store settings", "catalog search", "catalog fulltext search", "customer accounts", "data api lockdown");
		assertThat(Arrays.stream(all).map(m -> m.getState())).containsOnly(MigrationState.SUCCESS);
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
	}

	@Test
	void theAppliedMigrationsValidateAgainstTheirChecksums_noRepairNeeded() {
		flyway.validate();   // throws if a released migration was edited
	}
}
