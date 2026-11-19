-- Staff second-factor RECOVERY CODES. Supabase TOTP has no recovery codes and the backend never holds the identity provider's service-role key, so the backend cannot
-- remove a lost authenticator factor. A member therefore keeps a handful of one-time codes made while signed in with the second factor. Redeeming one proves possession
-- of a secret only that member was shown, and lets THAT SIGN-IN SESSION (not the account) use the administration for a short, configured time while an operator
-- removes the lost factor. Codes are random (about 78 bits), stored only as a SHA-256 hash bound to the member, consumed exactly once, and every use is audited.

create table staff_recovery_codes (
    id            uuid        primary key,
    user_id       uuid        not null,
    code_hash     varchar(64) not null unique check (length(code_hash) = 64),
    batch_id      uuid        not null,
    created_at    timestamptz not null,
    used_at       timestamptz,
    superseded_at timestamptz,
    check (used_at is null or superseded_at is null)
);
create index staff_recovery_codes_unused_idx on staff_recovery_codes (user_id) where used_at is null and superseded_at is null;
alter table staff_recovery_codes enable row level security;

-- One row per sign-in session that redeemed a code. `session_id` is the identity provider's session id from the signed token, so the permission dies with the session
-- (and with expires_at, whichever is first) and is never carried to another device or a later sign-in.
create table staff_recovery_sessions (
    id          uuid         primary key,
    user_id     uuid         not null,
    session_id  varchar(100) not null,
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    unique (user_id, session_id),
    check (expires_at > created_at)
);
alter table staff_recovery_sessions enable row level security;
