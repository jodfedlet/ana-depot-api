# ADR 0013 - Supabase PostgreSQL: connection mode and Data API lockdown
Status: accepted. Details and verification: [../supabase-database.md](../supabase-database.md).

**Context.** Production data lives in a Supabase-hosted PostgreSQL. Two Supabase-specific risks matter to this backend: (1) the connection path (direct, session pooler, transaction pooler) changes which
PostgreSQL features behave as the code assumes; (2) Supabase exposes the `public` schema through an auto-generated REST/GraphQL API and grants `anon`/`authenticated` broad default privileges there, so a
table without Row Level Security is readable and writable by anyone holding the public project key. The backend never uses that API.

**Decision.**
1. **Connection.** The application connects with its own role over JDBC using the **direct connection** (IPv6) or, on IPv4-only hosts, the **session-mode pooler**; Flyway uses the same connection.
   The transaction-mode pooler is allowed only for the application when the connection budget demands it, with `prepareThreshold=0`, and then Flyway must use a direct or session connection (it takes an advisory lock for the
   duration of a migration). Our row locks (`FOR UPDATE`, `FOR UPDATE SKIP LOCKED`) are transaction-scoped and therefore correct in every mode; the application uses no session state, advisory locks or `LISTEN/NOTIFY`.
2. **Role.** A dedicated role that owns the tables runs both Flyway and the application. Never `service_role`, never an API key.
3. **Lockdown in a migration (V10), not by documentation.** RLS enabled with **no policies** on every application table; all privileges on `public` revoked from `anon`, `authenticated`, `service_role` (including default privileges and
   PUBLIC execute on functions). Both are conditional on the roles existing, so plain PostgreSQL and Testcontainers run the same migration unchanged. The Flyway history table is excluded from RLS (Flyway holds a lock on it during migration; altering it hangs) and covered by the revokes.
   `FORCE ROW LEVEL SECURITY` is not used.
4. **The runtime role must own the tables or have `BYPASSRLS`.** Otherwise RLS (no policies) makes every table look empty to the application. This is documented, checked by the manual verification list, and by the smoke run on the Supabase Postgres image.
5. **Defence in depth outside the database:** disable or narrow the Data API in the Supabase dashboard (documented checklist); the API holds no Supabase key.
6. `service_role` is revoked as well although it bypasses RLS by design: the API does not use PostgREST at all, so granting it anything would only widen the blast radius of a leaked key. A future feature that needs it must grant per table, explicitly.

**Alternatives.** Policies that allow nothing (equivalent to no policies, more noise); revoking only (a later `GRANT`, or a table created as `supabase_admin`, silently re-opens access: RLS is the second lock); RLS only (default privileges still
invite mistakes); hosting Postgres elsewhere (outside the decision); transaction pooler for everything (Flyway and prepared statements need workarounds for no benefit at this scale).

**Consequences.** Every future table must `enable row level security` in its own migration (`MigrationRlsConventionTest` reads the migration files and fails when a table created after V10 does not; `DataApiLockdownIntegrationTest` proves the effect of V10 itself). Tables created by `supabase_admin` (dashboard table editor) keep Supabase's default grants
because V10 cannot change another role's defaults: do not create application tables in the dashboard. Pooler behaviour and the hosted Data API exposure are documented but **not verified against a hosted project**.
