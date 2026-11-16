-- Part 6: store settings. Only the settings that have a real, decided model are stored here (today: the pickup configuration).
-- No default row is inserted: until the owner saves the settings, pickup is "not configured" and no value is invented.
create table store_settings (
    setting_key varchar(60) primary key,
    value       jsonb       not null check (jsonb_typeof(value) = 'object'),
    version     bigint      not null default 0,      -- optimistic concurrency: an editor must save the version it loaded
    updated_by  uuid,                                -- identity-provider user id of the staff member
    updated_at  timestamptz not null
);
