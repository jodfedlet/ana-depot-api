# PostgreSQL: provider-neutral connection and operations

Decision record: [ADR 0016, Database Provider Independence](adr/0016-database-provider-independence.md). Schema and constraints: [database.md](database.md).
Supabase-only operations (its poolers, Data API, the `ana_app` walkthrough): [supabase-database.md](supabase-database.md).

**The guarantee.** The backend needs *standard PostgreSQL* and nothing else. The same jar runs against Supabase PostgreSQL, Azure Database for PostgreSQL, AWS RDS for PostgreSQL
and a plain PostgreSQL in Docker. Changing provider = changing the environment variables below (and, at most, operational tuning). It never touches the domain, the application
services, the API or the migrations. Authentication (Supabase Auth) and object storage (Cloudflare R2) are independent of where the database runs.

## 1. The database contract

| Requirement | Value | Why / how it is checked |
|---|---|---|
| Engine | PostgreSQL **14 or newer** (suite verified on 14, 16, 17 and 18; see section 7) | `PlainPostgresCompatibilityIntegrationTest` asserts `server_version_num >= 140000` |
| Extensions | **none** (only the built-in `plpgsql`) | the test asserts `pg_extension` holds only `plpgsql`; `ProviderIndependenceConventionTest` forbids `create extension` in new migrations |
| Encoding | `UTF8` (French and Haitian Creole text) | asserted by the same test |
| Schema | `public` (the connection's default schema; V11 is written for it) | do not point the role's `search_path` elsewhere |
| Roles | **one role** that is not a superuser, owns the database objects and runs **both** Flyway and the application | section 3 |
| Features used | `jsonb`, `tsvector` + GIN (`simple` configuration), identity columns, deferrable constraints, `plpgsql` triggers, partial indexes, `SELECT ... FOR UPDATE [SKIP LOCKED]`, Row Level Security (enabled, no policies) | all core PostgreSQL |
| Not used | extensions, advisory locks in application code (Flyway takes its own), `LISTEN/NOTIFY`, `SET` session state, provider roles/schemas/functions, logical replication, `COPY ... PROGRAM` | keeps every pooler and managed service viable |

## 2. Configuration (the only thing that changes between providers)

| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<host>:<port>/<database>?sslmode=...` (profile `prod` refuses to start without it, and refuses a non-PostgreSQL URL) |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | the application role. Secrets: environment or secret manager only, never committed or logged |
| `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` | **optional**. Unset = Flyway uses the datasource above (the recommended setup). Set the URL only to bypass a transaction pooler for migrations; keep the **same role** (section 3) |
| `SPRING_DATASOURCE_HIKARI_*` | pool tuning without a code change, e.g. `..._MAXIMUM_POOL_SIZE`, `..._KEEPALIVE_TIME`, `..._MAX_LIFETIME` |

No host name, user or credential is written in the repository (`ProviderIndependenceConventionTest` fails on a provider host name in `application*.yaml`).
The profile `prod` holds operational settings only (pool limits, JSON logs, startup guard); there is **no per-provider profile and no per-provider build**.

### TLS (standard pgjdbc parameters, valid on every provider)
| Level | URL suffix | Notes |
|---|---|---|
| Encrypted, server not verified | `?sslmode=require` | minimum for any hosted database; `ProductionConfigGuard` warns when no `sslmode` is present |
| Encrypted and verified | `?sslmode=verify-full&sslrootcert=/path/to/ca.pem` | ship the provider's public CA certificate with the deployment (it is not a secret). Use the CA bundle the provider publishes for your instance |
| Plain, local only | (none) | `docker compose up postgres`, Testcontainers |

### Example URLs (placeholders; values come from your provider's console)
```
Supabase  jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require          (session pooler: see supabase-database.md)
Azure     jdbc:postgresql://<server>.postgres.database.azure.com:5432/<database>?sslmode=require
AWS RDS   jdbc:postgresql://<instance>.<id>.<region>.rds.amazonaws.com:5432/<database>?sslmode=verify-full&sslrootcert=/etc/ssl/rds-ca-bundle.pem
Docker    jdbc:postgresql://localhost:5432/ana_depot
```

## 3. The role (the same on every provider)

Create one login role, make it own the application database, and let it create objects in `public`:
```sql
create role ana_app login password '<generated>';      -- not superuser, not bypassrls, not createdb
create database <database> owner ana_app;              -- or use an existing database and: alter database <database> owner to ana_app;
-- Only if the database already existed and is owned by someone else (PostgreSQL 15+ no longer lets everyone create in public):
grant usage, create on schema public to ana_app;
```
**Why one owning role.** V11 enables Row Level Security with **no policies** on every application table (it protects against any other role that is ever granted access, including
Supabase's Data API roles; ADR 0013, ADR 0016). The table owner is exempt from RLS; any other role without `BYPASSRLS` would see **empty tables** (not an error: empty). So the role that
*runs* the application must be the role that *ran Flyway*. `ProductionConfigGuard` warns when `SPRING_FLYWAY_USER` differs from `SPRING_DATASOURCE_USERNAME`. Never `FORCE ROW LEVEL SECURITY`.
A least-privilege split (migration owner vs. DML-only runtime role) is **not supported** by the current lockdown; it needs a new decision (ADR) and policies.

## 4. Pool and connection lifetime

Defaults (`application.yaml` and profile `prod`) are provider-neutral: 10 connections per instance, `max-lifetime` 25 min, `keepalive-time` 5 min, every network wait bounded
(`connectTimeout` 5 s, `socketTimeout` 60 s). Total server connections = instances x pool size: keep it under the instance's `max_connections`
(smaller sizes of every managed service allow few dozen). Idle-connection cut-offs differ per network path; if a platform drops idle TCP connections sooner than 5 minutes
(some cloud load balancers do), lower `SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME` (e.g. `120000`). That is configuration, not code.

## 5. Poolers and proxies

Prefer a **direct** connection. If a transaction-mode pooler is unavoidable (PgBouncer, Supavisor, a managed built-in pooler such as the one many providers offer on port 6432 or 6543, a connection proxy):
add `prepareThreshold=0` to the application URL and run Flyway through a **direct or session** connection (`SPRING_FLYWAY_URL`), because Flyway holds a session advisory lock for the
whole migration. The row locks used by the application are transaction-scoped and safe in every mode (reasoning in [supabase-database.md](supabase-database.md) section 3; not load-tested through any pooler).

## 6. Provider notes

| Provider | Role/permissions | TLS | Status |
|---|---|---|---|
| Docker / self-hosted | `compose.yaml` (`postgres:17-alpine`) | optional | **verified** (every test run) |
| Supabase | dedicated owner role (not `postgres`, never `service_role`); V11 also revokes the Data API roles ([supabase-database.md](supabase-database.md)) | `require` / `verify-full` with the project CA | schema and application **verified on the Supabase Postgres image**; the hosted service itself is **not verified** |
| Azure Database for PostgreSQL (Flexible Server) | the admin role belongs to `azure_pg_admin`, not superuser: create `ana_app` as in section 3. Server parameter `require_secure_transport` is on by default | `require`; verify with the published root CA | **not verified live** (architecturally compatible: standard PostgreSQL, no extension, no provider role) |
| AWS RDS for PostgreSQL / Aurora PostgreSQL | the master user belongs to `rds_superuser`, not superuser: create `ana_app` as in section 3 | `require`, or `verify-full` with the RDS CA bundle (`rds.force_ssl` is on by default in recent versions) | **not verified live** (same reasoning; Aurora not reviewed separately) |

Provider-specific things you may need to do, all outside the application: allow the backend's network address in the firewall/security group, enable backups and point-in-time recovery
([operations.md](operations.md)), size `max_connections`, choose the maintenance window. None changes the code.

## 7. How it was verified, and what was not

| Check | How | Result |
|---|---|---|
| Whole suite on plain PostgreSQL 17, container superuser (default) | `mvn test` | 572 tests, 0 failures, 7 skipped (opt-in real-R2 and performance tests) |
| Whole suite as a hosted-style role (non-superuser owner, no `BYPASSRLS`), PostgreSQL 17 and 18 | `mvn test -Dana.test.postgres.restricted-role=true [-Dana.test.postgres.image=postgres:18-alpine]` | 572 tests, 0 failures each |
| Same, PostgreSQL 16 | `... -Dana.test.postgres.image=postgres:16-alpine` | 1 error in a MinIO upload test (HTTP to the object-storage container, not the database); it passes alone and fails intermittently on the default configuration too. Everything else passed |
| Same, PostgreSQL 14 | `... -Dana.test.postgres.image=postgres:14-alpine` | everything passed except `DataApiLockdownIntegrationTest`: before PostgreSQL 16 a `CREATEROLE` role cannot `SET ROLE` to the simulated roles the test creates (a harness limit, not the application). That class passes on 14 in default mode (superuser) |
| No provider role/schema/extension required; no provider name in core, migrations, configuration | `PlainPostgresCompatibilityIntegrationTest`, `ProviderIndependenceConventionTest` | automated |
| Supabase roles present: V11 revokes them, idempotent | `DataApiLockdownIntegrationTest` (simulated roles) | automated |
| **Hosted Supabase, Azure Database for PostgreSQL, AWS RDS** (TLS chain, poolers, parameter groups, firewalls, plan limits) | live connection with the same jar | **NOT VERIFIED**: needs a real instance of each. Run the checklist below |

### Live provider checklist (run once per provider, record date and result in the deployment notes)
1. Start the packaged jar with `SPRING_PROFILES_ACTIVE=prod` and only the variables of section 2 changed. **Expected:** Flyway applies every migration on the empty database, `ddl-auto=validate` passes, `GET /actuator/health/readiness` is `UP`, `ProductionConfigGuard` prints no TLS or pooler warning.
2. As the application role: `select rolsuper, rolbypassrls from pg_roles where rolname = current_user;` (both false is fine) and `select count(*) from products;` after creating one product (the role sees its data).
3. `select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history' and not c.relrowsecurity;` returns `0`.
4. Create a reservation with an `Idempotency-Key`, repeat it (same reservation returned), record a payment in each currency, and read the reconciliation endpoint.

## 8. Known limitations

* Hosted Azure and AWS compatibility is architectural (standard PostgreSQL, no extension, no provider role) and covered by tests on plain PostgreSQL 14-17 under a hosted-style role; it has **not** been run on those services.
* One owning role runs Flyway and the application (section 3). Separate migration and runtime roles are not supported by V11's lockdown.
* PostgreSQL below 14 is not verified. Test harness only: in restricted-role mode `DataApiLockdownIntegrationTest` needs PostgreSQL 16+ (it simulates Supabase roles, which requires switching roles); run it on 14/15 in default mode.
* `R2AbandonedUploadCleanupIntegrationTest` (MinIO, unrelated to the database) is intermittently flaky on a cold container; reported, not changed.
* Pooler behaviour (any provider) is reasoned, not load-tested.
* Backups, PITR, failover and restore time are provider features and are verified per provider (`dev/restore-test.sh` exercises a logical dump/restore on the Supabase image only).
