-- Default test database (plain PostgreSQL, no hosting-provider roles). One harmless NOLOGIN role with an arbitrary name exists so that
-- PlainPostgresCompatibilityIntegrationTest can prove that Row Level Security hides rows from ANY other role, not only from Supabase's anon/authenticated.
create role ana_probe_reader nologin;
