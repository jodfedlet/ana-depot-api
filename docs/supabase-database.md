# Supabase PostgreSQL: connection, safety and verification

The backend uses Supabase **only as a managed PostgreSQL** (and Supabase Auth for identity, see [auth.md](auth.md)). It talks to the database over JDBC with its own
role. It does **not** use Supabase's auto-generated REST/GraphQL API ("Data API"), Supabase Storage (images go to Cloudflare R2, [storage-r2.md](storage-r2.md)),
Realtime or Edge Functions.

## 1. Recommendation (one choice)

| | |
|---|---|
| **Application connection** | the **direct connection** (`db.<project-ref>.supabase.co:5432`) when the host running the backend has IPv6 (or the project has the IPv4 add-on); otherwise the **Supavisor session-mode pooler** (`<region>.pooler.supabase.com:5432`, user `ana_app.<project-ref>`). **Not** the transaction-mode pooler. |
| **Flyway** | the **same** connection as the application (both are session-safe). No separate Flyway URL is needed. |
| **Role** | a dedicated role `ana_app` that **owns** the tables (it runs Flyway), not `postgres` and never `service_role` (section 6). |
| **TLS** | `sslmode=require` at minimum, `verify-full` with the Supabase root certificate when you can ship it (section 4). |
| **Pool** | Hikari, 10 connections per instance, retired before any network device can drop them (section 5). |

Why not the transaction pooler: this application's correctness relies on transaction-scoped row locks (fine behind it) **and** on a migration tool that takes a
session-level advisory lock (not fine). One connection string that is safe for everything is simpler to operate than two. The transaction pooler is only worth it when
(instances x pool size) would exceed the database's connection limit; then use it for the application **only** (section 3).

## 2. The three ways to connect (Supabase documentation, "Connect to your database")

| Mode | Host / port | Network | Session state | Notes |
|---|---|---|---|---|
| Direct | `db.<ref>.supabase.co:5432` | IPv6 (IPv4 needs the paid add-on) | full | one Postgres backend per client connection |
| Session pooler (Supavisor) | `<region>.pooler.supabase.com:5432`, user `<role>.<ref>` | IPv4 and IPv6 | full (the client keeps its server connection for the whole session) | Supabase's recommendation for persistent backends on IPv4-only hosts |
| Transaction pooler (Supavisor) | `<region>.pooler.supabase.com:6543` | IPv4 and IPv6 | **none between transactions**: `SET`, session advisory locks, `LISTEN/NOTIFY`, temporary tables are lost | meant for serverless/edge clients that open many short connections |

(Port 6543 used to offer session mode; Supabase deprecated that on 2025-02-28. Older tutorials are wrong about it.)

## 3. What is safe where (specific to this code base)

The code uses exactly these concurrency primitives (`grep` of `src/main`): `SELECT ... FOR UPDATE` through JPA `PESSIMISTIC_WRITE` on inventory rows, reservation rows and upload
intents; `FOR UPDATE SKIP LOCKED` to claim outbox events; optimistic `version` columns; unique constraints (idempotency, rate limiter). **No advisory locks, no `SET`, no `LISTEN`.**

| Primitive | Direct / session pooler | Transaction pooler |
|---|---|---|
| `SELECT ... FOR UPDATE` on stock rows (stable lock order, ADR 0009) | safe | **safe**: the lock lives inside one transaction, and a transaction runs on one server connection from `BEGIN` to `COMMIT`; losing the connection between transactions loses nothing the lock protected |
| `FOR UPDATE SKIP LOCKED` outbox claim (ADR 0003: claim with a lease, commit, handle, mark processed) | safe | **safe** for the same reason; the lease is stored in the row, not in the session |
| Unique-constraint idempotency and rate-limit counters, `REQUIRES_NEW` transactions | safe | safe (each is a transaction) |
| Flyway (`migrate`) | safe | **not safe**: Flyway holds a database advisory lock for the duration of the migration, a session-level feature; run it on direct/session |
| pgjdbc server-side prepared statements (switch on after 5 executions of a statement) | safe | **unsafe unless disabled**: a prepared statement belongs to a server connection that the pooler may give to someone else. Add `prepareThreshold=0` to the URL |
| `jpa.properties.hibernate.jdbc.time_zone=UTC` | safe | safe (it is applied by the driver per statement, not with `SET`) |

If you do use the transaction pooler for the application:
```
SPRING_DATASOURCE_URL=jdbc:postgresql://<region>.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0
SPRING_DATASOURCE_USERNAME=ana_app.<project-ref>
SPRING_FLYWAY_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require        # or the session pooler on :5432
SPRING_FLYWAY_USER=ana_app            # (ana_app.<project-ref> on the session pooler)
```
`ProductionConfigGuard` logs a warning when the URL looks like port 6543 without `prepareThreshold=0`.
**Honest limits of this document:** the locking claims follow from how PostgreSQL transactions and the pooler's documented transaction semantics work; they were **not**
load-tested through a real Supavisor. The concurrency tests in this repository ran against plain PostgreSQL and the Supabase Postgres image (section 8), not through Supavisor.

## 4. TLS

Supabase accepts only TLS connections from outside its network. Minimum: `?sslmode=require` (encrypted, server identity **not** checked). Stronger: download the project's root
certificate (Dashboard > Project Settings > Database > SSL configuration), store it with the deployment (it is public, not a secret) and use
`?sslmode=verify-full&sslrootcert=/path/to/prod-ca-2021.crt`. `ProductionConfigGuard` warns when the URL has no `sslmode`.

## 5. Connection pool (Hikari), per profile

| Setting | `local` | `prod` | Why |
|---|---|---|---|
| `maximum-pool-size` | 5 | 10 | Total server connections = instances x pool size. Compare with the plan's limit (Dashboard > Database > Connections shows it; small compute sizes allow few dozen direct connections and Supabase's own services use some). Two instances x 10 = 20. |
| `minimum-idle` | 1 | 2 | warm connections for a quiet store |
| `connection-timeout` | 5 s | 5 s | a request should fail fast instead of queueing behind a saturated pool |
| `max-lifetime` | default | 25 min | retire connections before a pooler/load balancer does, so a request never meets a half-dead connection |
| `idle-timeout` / `keepalive-time` | default | 5 min / 5 min | a cheap ping keeps silent network drops from surfacing as user errors |
| `initialization-fail-timeout` | 10 s | 10 s | the application refuses to start when the database cannot be reached |

Override with `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (Spring's relaxed binding) without a code change. Tests use their own pool (30) because the concurrency tests run ~24 simultaneous use cases.

## 6. The database role

Create a role that owns the application objects and use it for both Flyway and the application:
```sql
-- run once as postgres (Dashboard > SQL editor). Choose the password yourself; do not paste a real one into a ticket.
create role ana_app login password '<generated>' ;
grant usage, create on schema public to ana_app;     -- PostgreSQL 15+ no longer lets everyone create in public
-- optional: make the schema owned by it so future migrations never need more
```
* **Owner = runtime role is required.** V11 enables Row Level Security on every table with no policies. The table owner is exempt from RLS, so the application works; a runtime role
  that is *not* the owner and has no `BYPASSRLS` would see **empty tables**. Supabase's `postgres` has `BYPASSRLS`; `ana_app` works because it owns the tables.
  (Never `FORCE ROW LEVEL SECURITY`: it would lock the owner out too.)
* Never use `service_role` or the anon/publishable keys for this connection. The API holds **no Supabase API key at all**: it verifies tokens against the public JWKS.
* With the pooler the username carries the project reference: `ana_app.<project-ref>`.

## 7. Data API lockdown (V11) and how to verify it on a real project

Supabase publishes the `public` schema through PostgREST by default, and gives `anon` and `authenticated` broad privileges on it (and on every table created later). Anyone with the
project's public key could therefore read or write tables unless Row Level Security or revoked privileges stop them. V11 closes this twice (ADR 0013):
RLS enabled **without policies** on every application table, and `REVOKE ALL` on all tables, sequences and functions of `public` from `anon`, `authenticated` and `service_role`, plus the same for
**default privileges** (tables created by later migrations start closed). It only touches those roles when they exist, so plain PostgreSQL is unaffected.

**Also turn the exposure off in the dashboard** (defence in depth, not a replacement): Project Settings > API: remove `public` from *Exposed schemas* or disable the Data API entirely, because the API server never uses it.

**Verification on a real Supabase database (manual; this repository's automated test cannot prove it)**, as `postgres` in the SQL editor, after the first deployment:
```sql
-- 1. no application table without RLS, and no policies
select relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history' and not c.relrowsecurity;          -- expect 0 rows
select count(*) from pg_policies where schemaname = 'public';                                                              -- expect 0
-- 2. the API roles have nothing
select grantee, table_name, privilege_type from information_schema.role_table_grants
 where table_schema = 'public' and grantee in ('anon', 'authenticated', 'service_role');                                   -- expect 0 rows
-- 3. defaults for new tables
select defaclrole::regrole, defaclacl from pg_default_acl where defaclnamespace = 'public'::regnamespace;                  -- no entry may mention anon/authenticated/service_role
-- 4. behaviour as an API role
begin; set local role anon; select count(*) from products; rollback;                                                         -- expect: permission denied
```
Then from outside, with the **public** (anon) key from the dashboard:
```bash
curl -i "https://<project-ref>.supabase.co/rest/v1/products?select=*" -H "apikey: <anon-key>"     # expect 401/403/404 or "permission denied", never rows
```
Record the date and result in the deployment notes.

## 8. What was verified, and how

| Check | Result |
|---|---|
| Flyway V1-V13 + `ddl-auto=validate` + application start on the **Supabase Postgres image** (`public.ecr.aws/supabase/postgres:17.6.1.064`, the image the Supabase CLI uses), throwaway container with its own database | see "Smoke on the Supabase Postgres image" below |
| V11 on plain PostgreSQL, simulated Supabase roles/default privileges, idempotency | automated: `DataApiLockdownIntegrationTest` (Testcontainers PostgreSQL 17) |
| `ProductionConfigGuard`, JWKS override, issuer derivation | automated unit tests |
| Anything against the **hosted** Supabase service (pooler behavior, TLS chain, Data API exposure, plan limits) | **NOT VERIFIED**: needs a real project; use the checklist above |

## Smoke on the Supabase Postgres image (run 2026-11-16)
A throwaway container of `public.ecr.aws/supabase/postgres:17.6.1.064` (PostgreSQL 17.6 with Supabase's roles and default privileges; started with `docker run`, **not** the auth work's
local stack, which was stopped and left untouched) and the packaged jar:

| Run | Result |
|---|---|
| jar, role `postgres`, database `postgres` (Supabase's real default ACLs: `anon`, `authenticated`, `service_role` have `arwdDxtm` on future tables of `public`) | Flyway applied V1-V11 without error, Hibernate `ddl-auto=validate` passed, `GET /actuator/health` UP, `GET /api/v1/products` empty page, `GET /api/v1/store/pickup` `not_configured` |
| afterwards, as `postgres` | tables without RLS: **0**; policies: **0**; grants to `anon/authenticated/service_role` on `public` tables: **0**; `set local role anon; select count(*) from products` -> **permission denied for table products**; `select ... for update skip locked` and `select ... for update` run fine |
| jar, **dedicated role `ana_app`** (owner of its database), profile `prod` (`SUPABASE_URL` set) | start-up OK in 5.8 s, health UP, empty product page, RLS everywhere, 0 API grants; `ProductionConfigGuard` printed its two warnings (no `sslmode` in the URL, no trusted proxies) |

Re-run on 2026-11-17 for the Phase 2 admin API (V12 reservation terms, V13 customer version; throwaway container `anadepot-smoke-pg`, removed afterwards; packaged jar, profile `local`, role `postgres`): Flyway applied **13 migrations**, `ddl-auto=validate` passed,
`GET /actuator/health` UP, `GET /api/v1/products` 200, `GET /api/v1/admin/products` and `/admin/reports/overview` without a token 401; afterwards: tables without RLS **only `flyway_schema_history`** (Flyway's own bookkeeping table), policies 0, grants to the API roles 0,
`set role anon; select count(*) from products` -> permission denied. The staff endpoints themselves were not exercised on this image (they run on `postgres:17-alpine` in the test suite).

Findings worth knowing:
* In `pg_default_acl` the entries created **by `supabase_admin`** still grant to `anon`/`authenticated`/`service_role`. V11 revokes the defaults of the role that runs it; it cannot (and must not try to)
  change another role's defaults. A table created through the dashboard's table editor (as `supabase_admin`) in `public` would start with those grants - it would still have no rows visible if you enable RLS on it,
  and the dashboard's "RLS disabled" warning flags it. Application tables are always created by Flyway as `ana_app`/`postgres`.
* A fresh database in this image has no Supabase default ACLs, so the default-privilege revocation is exercised only in the `postgres` database run above.
* **Not done:** the reservation/payment integration tests were not re-run against this image (they run on `postgres:17-alpine`, same major version); no load or pooler test; no hosted project.
