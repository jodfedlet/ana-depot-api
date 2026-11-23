-- "Hosted" role model (AWS RDS, Azure Database for PostgreSQL, Supabase's own application role, ...): the application owns ONE database and connects as an
-- ordinary role that is NOT a superuser and does NOT bypass Row Level Security. Selected with -Dana.test.postgres.restricted-role=true (docs/database-providers.md).
-- Flyway and the application both run as ana_app, exactly as the documentation prescribes.
--
-- CREATEROLE is granted ONLY because DataApiLockdownIntegrationTest creates (and drops) simulated Supabase roles; the application itself never needs it.
-- PostgreSQL 16+ no longer lets a CREATEROLE role SET ROLE to (or drop objects of) the roles it creates unless it grants itself that: createrole_self_grant does it.
-- The setting does not exist before 16, where CREATEROLE needs no such help.
create role ana_probe_reader nologin;
create role ana_app login password 'ana_app_test_only' nosuperuser nobypassrls nocreatedb createrole;
grant ana_probe_reader to ana_app;
create database ana_compat owner ana_app;
do $$
begin
    if current_setting('server_version_num')::int >= 160000 then
        execute 'alter role ana_app set createrole_self_grant = ''set, inherit''';
    end if;
end
$$;
