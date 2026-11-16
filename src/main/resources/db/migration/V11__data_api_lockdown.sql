-- Data API lockdown. The application connects to PostgreSQL directly (JDBC); NOBODY is meant to reach these tables through Supabase's
-- auto-generated REST/GraphQL "Data API" (PostgREST) with the public anon key or a customer's token. This migration makes sure of it, so
-- that a table added in the dashboard's "exposed schemas" or a mistaken GRANT cannot silently publish customer data.
--
--  1. Row Level Security is enabled, with NO policies, on every APPLICATION table of schema public (Flyway's own history table is left alone: Flyway
--     holds a lock on it while this migration runs, so altering it here would wait forever; step 2 revokes the API roles' privileges on it): a role that is not the owner (and has no BYPASSRLS)
--     sees no rows and can write none. The owner (our migration/runtime role) is exempt, so the application is unaffected.
--     FORCE ROW LEVEL SECURITY is deliberately NOT used (it would apply RLS to the owner too).
--  2. When the Supabase roles exist, every privilege on tables, sequences and functions of schema public is revoked from anon, authenticated and
--     service_role (the API never uses PostgREST, so none of them has any business here), and the DEFAULT privileges are revoked so tables
--     created by later migrations start closed. Functions also lose the implicit EXECUTE for PUBLIC.
--  3. Plain PostgreSQL (CI, local development, Testcontainers): the roles do not exist, the role-dependent part is skipped, RLS is enabled and
--     harmless. The script is idempotent: running it again changes nothing.
--
-- Requirement: the role that RUNS the application must own the tables (it created them through Flyway) or have BYPASSRLS (Supabase's postgres has it).
-- A runtime role that is neither would see EMPTY tables. docs/supabase-database.md explains how to verify this on a real Supabase database.

do $lockdown$
declare
    tbl record;
    api_role text;
begin
    -- 1. RLS on every application table, no policies
    for tbl in
        select c.relname
        from pg_catalog.pg_class c
        join pg_catalog.pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind in ('r', 'p') and c.relname <> 'flyway_schema_history'
    loop
        execute format('alter table public.%I enable row level security', tbl.relname);
    end loop;

    -- 2. Supabase API roles: nothing in schema public, now or for objects created later by this role
    foreach api_role in array array['anon', 'authenticated', 'service_role']
    loop
        if exists (select 1 from pg_catalog.pg_roles where rolname = api_role) then
            execute format('revoke all privileges on all tables in schema public from %I', api_role);
            execute format('revoke all privileges on all sequences in schema public from %I', api_role);
            execute format('revoke all privileges on all functions in schema public from %I', api_role);
            execute format('alter default privileges in schema public revoke all on tables from %I', api_role);
            execute format('alter default privileges in schema public revoke all on sequences from %I', api_role);
            execute format('alter default privileges in schema public revoke all on functions from %I', api_role);
        end if;
    end loop;

    if exists (select 1 from pg_catalog.pg_roles where rolname in ('anon', 'authenticated', 'service_role')) then
        -- PostgreSQL grants EXECUTE on new functions to PUBLIC, which anon/authenticated inherit: close that too (the owner keeps its own rights)
        revoke all privileges on all functions in schema public from public;
        alter default privileges in schema public revoke all on functions from public;
    end if;
end
$lockdown$;
