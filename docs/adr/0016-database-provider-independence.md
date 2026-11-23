# ADR 0016 - Database Provider Independence
Status: accepted. Operations and per-provider notes: [../database-providers.md](../database-providers.md). Complements [ADR 0013](0013-supabase-postgres-connection-and-data-api-lockdown.md) (the Supabase-specific part).

**Context.** Production data was first planned on Supabase PostgreSQL, and several documents and a startup warning spoke of "the" database as Supabase. The owner requires that the hosting provider of
the database can change (Supabase, Azure Database for PostgreSQL, AWS RDS for PostgreSQL, any standard PostgreSQL) without touching business logic, services, API behaviour or migrations.
An audit found the code already portable (no extension, no Supabase SDK/RPC/Data API call, no provider role in business code, persistence only through JPA); the remaining couplings were
documentation, wording and one undocumented assumption (below), so this ADR turns the accident into a guarantee and tests it.

**Decision.**
1. **The database contract is standard PostgreSQL 14 or newer**, UTF8, schema `public`, **no extensions**, accessed through Spring Data JPA / Hibernate (`ddl-auto=validate`), the PostgreSQL JDBC driver, HikariCP and Flyway only.
   No Supabase SDK, Data API or RPC; no provider role, schema, function or extension in business code or in new migrations.
2. **Switching provider is configuration only**: `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (+ optional `SPRING_FLYWAY_*`, `SPRING_DATASOURCE_HIKARI_*`, standard pgjdbc TLS parameters). One jar, one `prod` profile that holds operational values only;
   a provider-specific profile is allowed only for operational values, never for behaviour. No default host, no credential in the repository.
3. **Responsibilities.** *Flyway* owns the schema and reference data (never DDL by Hibernate; Hibernate only validates). *JPA* owns all business reads and writes. Exceptions stay as documented in AGENTS.md
   (`ProductSearchNative` ADR 0015, `FOR UPDATE SKIP LOCKED` claims, both standard PostgreSQL SQL executed through the `EntityManager`).
4. **Migration rules.** (M1) Released migrations are immutable. (M2) A migration is portable PostgreSQL: it runs unchanged on every supported provider. (M3) After V11, migrations contain schema and data only: no `create extension`,
   `create/alter role`, `grant/revoke`, `security definer`, tablespaces or provider names (`ProviderIndependenceConventionTest`); a genuine exception needs a new ADR. (M4) Provider-conditional behaviour is allowed only when it is
   a guarded no-op elsewhere and proven on plain PostgreSQL: V11 is the only case (its Supabase-role revokes run only if those roles exist). (M5) Every new table enables Row Level Security in its own migration (`MigrationRlsConventionTest`).
5. **Data isolation is portable.** The lockdown is PostgreSQL Row Level Security with no policies (V11, M5): it hides every table from *any* role that is not the owner, whatever the role is called
   (`PlainPostgresCompatibilityIntegrationTest` proves it with an arbitrary role, with a control that the grant alone would expose the rows). Supabase's role revokes are an additional layer, not the mechanism.
   Application authorization (staff membership table) never depended on database roles.
6. **One owning role.** The role that runs Flyway owns the tables and runs the application (the owner is exempt from RLS). A different runtime role without `BYPASSRLS` would see empty tables; this is documented,
   and `ProductionConfigGuard` warns when `SPRING_FLYWAY_USER` differs from the datasource user. A least-privilege migration-owner/runtime split is **not** supported without a new ADR (policies or `BYPASSRLS`).
7. **Hosting, authentication and object storage are three independent decisions.** Database: any standard PostgreSQL. Authentication: Supabase Auth (JWT/JWKS verification only; nothing about it touches the database
   provider; Supabase Auth + Azure/RDS PostgreSQL is a supported combination). Object storage: Cloudflare R2 (S3 API). Changing one never requires changing another.
8. **Poolers are optional.** The application works on a direct connection. A transaction pooler needs `prepareThreshold=0` for the application and a direct/session connection for Flyway (advisory lock). No provider pooler is required.

**Verification (local, standard PostgreSQL; see [database-providers.md](../database-providers.md) section 7).** The same suite runs unchanged, via `AbstractIntegrationTest`, on plain PostgreSQL in three configurations:
the default (container superuser), a hosted-style role (`-Dana.test.postgres.restricted-role=true`: non-superuser, non-`BYPASSRLS` owner) and other majors (`-Dana.test.postgres.image=postgres:<n>-alpine`).
Results of the run that accepted this ADR are recorded in section "Evidence" below.

**Alternatives.** An adapter or abstraction layer per provider (rejected: nothing differs, it would be speculative); dropping RLS/V11 because Supabase is no longer assumed (rejected: it is standard PostgreSQL and the only data-isolation layer at database level, and V11 cannot be edited);
a corrective migration (rejected: none needed, V11 is already a no-op for absent roles and every table is owned by the migrating role); a split migration/runtime role now (rejected: needs policies and a security review).

**Consequences.**
* New provider = new environment values, firewall, backups; no code, migration or test change.
* Azure and AWS RDS are **architecturally compatible and untested live**; hosted Supabase also remains untested live. The live checklist is in database-providers.md section 7.
* The tested floor is PostgreSQL 14 (14, 16, 17 and 18 run); Aurora PostgreSQL is not reviewed separately.
* Pooler behaviour and idle-connection cut-offs are tuned per deployment through `SPRING_DATASOURCE_HIKARI_*`.

## Evidence (local, 2026-11-23; no hosted provider was contacted)
* `mvn test` (PostgreSQL 17, container superuser): 572 tests, 0 failures, 7 skipped (opt-in real-R2 and performance tests).
* Hosted-style role (`ana_app`: non-superuser, non-`BYPASSRLS`, owns its database; Flyway and the application both connect as it): PostgreSQL 17 and 18, 572 tests, 0 failures.
* Same role model, PostgreSQL 16: all database tests pass; one MinIO upload test errored on an HTTP read from the storage container (intermittent, also seen on the default configuration; passes alone). PostgreSQL 14: all pass except
  `DataApiLockdownIntegrationTest`, which needs PostgreSQL 16+ to simulate role switching under a non-superuser (harness limit); it passes on 14 in default mode.
* Not verified: hosted Supabase, Azure Database for PostgreSQL, AWS RDS/Aurora (live connection, TLS chain, poolers, parameter groups).
