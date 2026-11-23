package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database contract of ADR 0016 ("Database Provider Independence"), checked against PostgreSQL itself on a PLAIN instance: no hosting-provider roles, schemas or
 * extensions exist, yet Flyway (V1..latest) migrated from empty, Hibernate validated the schema and the application started. Together with the rest of the suite (which
 * runs on the same container) this shows that nothing in the persistence layer needs Supabase, Azure or AWS database behaviour.
 *
 * <p>It runs unchanged under every configuration of {@link AbstractIntegrationTest}: another PostgreSQL major version ({@code -Dana.test.postgres.image}) and the
 * hosted role model, an ordinary owner role without superuser or BYPASSRLS ({@code -Dana.test.postgres.restricted-role=true}). It is NOT a test against a hosted
 * service: Azure Database for PostgreSQL and AWS RDS remain to be verified live (docs/database-providers.md).
 */
class PlainPostgresCompatibilityIntegrationTest extends AbstractIntegrationTest {

	/** Oldest major version the migrations and queries were verified on; hosted services still offer it (docs/database-providers.md). */
	private static final int MINIMUM_SERVER_VERSION_NUM = 140000;

	@PersistenceContext EntityManager em;
	@Autowired PlatformTransactionManager manager;

	private long count(String sql) {
		return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
	}

	@SuppressWarnings("unchecked")
	private List<String> names(String sql) {
		return (List<String>) em.createNativeQuery(sql).getResultList();
	}

	@Test
	void noHostingProviderRoleSchemaOrExtensionExistsAndNoneIsRequired() {
		assertThat(names("select rolname from pg_roles where rolname in ('anon', 'authenticated', 'service_role', 'supabase_admin', 'supabase_auth_admin', 'authenticator', 'pgbouncer') or rolname like 'azure%' or rolname like 'rds%'"))
				.as("roles of a hosting provider").isEmpty();
		assertThat(names("select nspname from pg_namespace where nspname in ('auth', 'storage', 'realtime', 'extensions', 'graphql', 'graphql_public', 'vault', 'pgsodium', 'supabase_functions', 'azure_pg_admin', 'aws_commons', 'aws_s3')"))
				.as("schemas of a hosting provider").isEmpty();
		assertThat(names("select extname from pg_extension")).as("the schema needs no extension: plpgsql ships with every PostgreSQL").containsExactly("plpgsql");
	}

	@Test
	void theServerIsStandardPostgreSqlNotOlderThanTheVerifiedFloorAndUsesUtf8() {
		assertThat(count("select current_setting('server_version_num')::int")).as("server_version_num").isGreaterThanOrEqualTo(MINIMUM_SERVER_VERSION_NUM);
		assertThat(names("select pg_encoding_to_char(encoding) from pg_database where datname = current_database()")).as("French and Haitian Creole text needs UTF8").containsExactly("UTF8");
	}

	@Test
	void theRuntimeRoleOwnsEveryApplicationTableWhichIsTheOnlyRoleRequirementOfRowLevelSecurity() {
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r'")).as("application tables").isGreaterThan(20);
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r' and pg_get_userbyid(c.relowner) <> current_user"))
				.as("tables not owned by the role the application runs as (it would see them EMPTY once row level security has no policy)").isZero();
		assertThat(count("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history' and not c.relrowsecurity"))
				.as("tables without row level security").isZero();
		assertThat(count("select count(*) from pg_policies where schemaname = 'public'")).as("policies: none, the lockdown is 'RLS with no policy'").isZero();
	}

	/** The lockdown is a property of PostgreSQL's Row Level Security, not of Supabase's role names: ANY role that is not the owner sees nothing, even if a grant was made by mistake. */
	@Test
	void rowLevelSecurityHidesDataFromAnyRoleThatIsNotTheOwnerWhateverItIsCalled() {
		stockedMattress(1);                                        // at least one product exists
		try {
			new TransactionTemplate(manager).executeWithoutResult(s -> {
				assertThat(count("select count(*) from products")).as("the owner (the application) sees its data").isPositive();
				em.createNativeQuery("grant usage on schema public to ana_probe_reader").executeUpdate();
				em.createNativeQuery("grant select on products to ana_probe_reader").executeUpdate();     // a mistaken grant to an arbitrary role...
				em.createNativeQuery("set local role ana_probe_reader").executeUpdate();
				assertThat(count("select count(*) from products")).as("...is still answered with no rows").isZero();
				em.createNativeQuery("reset role").executeUpdate();
				em.createNativeQuery("alter table products disable row level security").executeUpdate();   // control: without RLS the same grant WOULD expose the rows
				em.createNativeQuery("set local role ana_probe_reader").executeUpdate();
				assertThat(count("select count(*) from products")).as("control: the grant alone does expose rows, so RLS is what protects them").isPositive();
				em.createNativeQuery("reset role").executeUpdate();
				throw new IllegalStateException("rollback");     // grants and the RLS change never survive the test
			});
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).isEqualTo("rollback");
		}
		assertThat(count("select count(*) from pg_class where oid = 'public.products'::regclass and relrowsecurity")).as("RLS is back on after the rollback").isEqualTo(1);
	}
}
