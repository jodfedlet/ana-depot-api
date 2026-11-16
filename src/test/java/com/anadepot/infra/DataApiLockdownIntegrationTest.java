package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V10 on PLAIN PostgreSQL (the Testcontainers database has no Supabase roles when Flyway runs it). The Supabase roles are then CREATED here with the
 * privileges Supabase grants by default, the migration script is run again (it is idempotent), and PostgreSQL itself is asked what anon / authenticated /
 * service_role can do. This simulates the Supabase role setup; it is NOT a test against a real Supabase database (docs/supabase-database.md has the
 * manual verification for that).
 */
class DataApiLockdownIntegrationTest extends AbstractIntegrationTest {

	private static final List<String> ROLES = List.of("anon", "authenticated", "service_role");

	@PersistenceContext EntityManager em;
	@Autowired PlatformTransactionManager manager;

	private TransactionTemplate tx() {
		return new TransactionTemplate(manager);
	}

	private long count(String sql) {
		return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
	}

	private static String script() throws IOException {
		return new ClassPathResource("db/migration/V10__data_api_lockdown.sql").getContentAsString(StandardCharsets.UTF_8);
	}

	/** What a fresh Supabase project gives its API roles: usage on the schema and ALL on everything (also by default for future objects). */
	private void simulateSupabaseRoles() {
		tx().executeWithoutResult(s -> {
			for (var role : ROLES) {
				em.createNativeQuery("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + role + "') then create role " + role + " nologin; end if; end $$").executeUpdate();
				em.createNativeQuery("grant usage on schema public to " + role).executeUpdate();
				em.createNativeQuery("grant all on all tables in schema public to " + role).executeUpdate();
				em.createNativeQuery("grant all on all sequences in schema public to " + role).executeUpdate();
				em.createNativeQuery("grant all on all functions in schema public to " + role).executeUpdate();
				em.createNativeQuery("alter default privileges in schema public grant all on tables to " + role).executeUpdate();
				em.createNativeQuery("alter default privileges in schema public grant all on sequences to " + role).executeUpdate();
				em.createNativeQuery("alter default privileges in schema public grant all on functions to " + role).executeUpdate();
			}
		});
	}

	private void applyLockdown() throws IOException {
		var sql = script();
		tx().executeWithoutResult(s -> em.createNativeQuery(sql).executeUpdate());
	}

	@BeforeEach
	void setUp() throws IOException {
		simulateSupabaseRoles();
		assertThat(count("select count(*) from information_schema.role_table_grants where grantee = 'anon' and table_schema = 'public'")).as("simulated Supabase grants are in place").isPositive();
		applyLockdown();
	}

	@AfterAll
	static void removeSimulatedRoles(@Autowired PlatformTransactionManager manager, @Autowired EntityManager em) {
		new TransactionTemplate(manager).executeWithoutResult(s -> {
			for (var role : ROLES) {
				em.createNativeQuery("drop owned by " + role).executeUpdate();
				em.createNativeQuery("drop role if exists " + role).executeUpdate();
			}
		});
	}

	@Test
	void rowLevelSecurityIsOnForEveryTableAndThereAreNoPolicies() {
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p') and c.relname <> 'flyway_schema_history' and not c.relrowsecurity"))
				.as("application tables of schema public without RLS (Flyway's history table is covered by the revokes)").isZero();
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p') and c.relforcerowsecurity"))
				.as("FORCE RLS would lock the owner out").isZero();
		assertThat(count("select count(*) from pg_policies where schemaname = 'public'")).as("policies").isZero();
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r'")).as("application tables exist").isGreaterThan(20);
	}

	@Test
	void theApiRolesHaveNoPrivilegeOnAnyTableSequenceOrFunctionOfSchemaPublic() {
		for (var role : ROLES) {
			assertThat(count("select count(*) from information_schema.role_table_grants where grantee = '" + role + "' and table_schema = 'public'")).as(role + " table grants").isZero();
			assertThat(count("select count(*) from information_schema.role_usage_grants where grantee = '" + role + "' and object_schema = 'public'")).as(role + " sequence/usage grants").isZero();
			assertThat(count("select count(*) from information_schema.role_routine_grants where grantee = '" + role + "' and routine_schema = 'public'")).as(role + " function grants").isZero();
			assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r' and has_table_privilege('" + role + "', c.oid, 'select,insert,update,delete')"))
					.as(role + " effective table privileges").isZero();
		}
	}

	@Test
	void tablesCreatedLaterStartClosedBecauseTheDefaultPrivilegesWereRevoked() {
		try {
			tx().executeWithoutResult(s -> {
				em.createNativeQuery("create table public.lockdown_probe (id integer)").executeUpdate();
				for (var role : ROLES) {
					assertThat(count("select count(*) from pg_class where oid = 'public.lockdown_probe'::regclass and has_table_privilege('" + role + "', oid, 'select')")).as(role).isZero();
				}
				throw new IllegalStateException("rollback");     // the probe table never survives
			});
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).isEqualTo("rollback");
		}
	}

	@Test
	void anAnonymousApiRequestIsRefusedAndEvenAMistakenGrantShowsNoRows() {
		stockedMattress(1);                                        // at least one product exists
		DatabaseAssertions.assertRejected(manager, "permission denied", () -> {
			em.createNativeQuery("set local role anon").executeUpdate();
			em.createNativeQuery("select count(*) from products").getSingleResult();
		});
		try {
			tx().executeWithoutResult(s -> {
				assertThat(count("select count(*) from products")).as("the owner (the application) still sees its data").isPositive();
				em.createNativeQuery("grant select on products to authenticated").executeUpdate();   // somebody grants by mistake...
				em.createNativeQuery("set local role authenticated").executeUpdate();
				assertThat(count("select count(*) from products")).as("...RLS with no policy still returns nothing").isZero();
				em.createNativeQuery("reset role").executeUpdate();
				throw new IllegalStateException("rollback");     // the mistaken grant is rolled back with the transaction
			});
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).isEqualTo("rollback");
		}
	}

	@Test
	void runningTheScriptAgainChangesNothing() throws IOException {
		var before = count("select count(*) from information_schema.role_table_grants where grantee in ('anon', 'authenticated', 'service_role')");
		applyLockdown();
		applyLockdown();
		assertThat(count("select count(*) from information_schema.role_table_grants where grantee in ('anon', 'authenticated', 'service_role')")).isEqualTo(before).isZero();
		assertThat(count("select count(*) from pg_policies where schemaname = 'public'")).isZero();
	}
}
