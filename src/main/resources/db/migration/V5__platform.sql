-- Part 5: platform tables for reliability and security standards (docs/adr).

-- Transactional outbox: written in the same transaction as the business change, delivered later at least once.
create table outbox_events (
    seq               bigint generated always as identity unique,   -- global order; per-aggregate order follows it
    id                uuid         primary key,                     -- stable event id: consumers deduplicate on it
    event_type        varchar(80)  not null,
    aggregate_type    varchar(40)  not null,
    aggregate_id      uuid         not null,
    aggregate_version bigint       not null,
    payload           jsonb        not null,
    correlation_id    varvarchar(64),
    created_at        timestamptz  not null,
    status            varchar(12)  not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'PROCESSED', 'DEAD')),
    attempts          integer      not null default 0,
    next_attempt_at   timestamptz  not null,
    lease_until       timestamptz,                                  -- a dispatcher that dies leaves an expired lease: the event is re-claimed
    last_error        text,
    processed_at      timestamptz
);
create index outbox_due_idx on outbox_events (status, next_attempt_at, seq);
create index outbox_aggregate_idx on outbox_events (aggregate_id, seq);

-- Server-side idempotency: unique per actor scope + operation + key. The row is committed with the business change.
create table idempotency_keys (
    id                  uuid         primary key,
    actor_scope         varchar(100) not null,
    operation           varchar(60)  not null,
    idempotency_key     varchar(128) not null,
    request_fingerprint varchar(64)     not null,
    resource_id         uuid         not null,
    created_at          timestamptz  not null,
    expires_at          timestamptz  not null,
    unique (actor_scope, operation, idempotency_key)
);
create index idempotency_expiry_idx on idempotency_keys (expires_at);

-- Deduplication of incoming/outgoing events per consumer (webhooks, outbox consumers).
create table processed_events (
    id                   uuid         primary key,
    consumer             varchar(100) not null,
    event_id             varchar(128) not null,
    first_processed_at   timestamptz  not null,
    duplicate_count      integer      not null default 0,
    last_duplicate_at    timestamptz,
    unique (consumer, event_id)
);

-- Immutable audit trail of sensitive operations.
create table audit_events (
    id              uuid         primary key,
    occurred_at     timestamptz  not null,
    actor_kind      varchar(10)  not null check (actor_kind in ('CUSTOMER', 'STAFF', 'SYSTEM')),
    actor_user_id   uuid,
    action          varchar(60)  not null,
    target_type     varchar(40)  not null,
    target_id       varvarchar(64)  not null,
    previous_values jsonb        not null default '{}'::jsonb,
    new_values      jsonb        not null default '{}'::jsonb,
    reason          text,
    correlation_id  varvarchar(64)
);
create index audit_target_idx on audit_events (target_type, target_id, occurred_at);
create trigger audit_events_immutable before update or delete on audit_events for each row execute function prevent_mutation();

-- Distributed rate limiting: fixed-window counters shared by every backend instance.
create table rate_limit_counters (
    bucket        varchar(200) not null,
    window_start  timestamptz  not null,
    request_count integer      not null default 0,
    primary key (bucket, window_start)
);

-- Presigned-upload lifecycle: the backend decides the object key; the client never does.
create table upload_intents (
    id                    uuid         primary key,
    purpose               varchar(30)  not null check (purpose in ('PRODUCT_IMAGE')),
    resource_id           uuid         not null,
    object_key            varchar(300) not null unique,
    declared_content_type varchar(100) not null,
    max_bytes             bigint       not null check (max_bytes > 0),
    status                varchar(12)  not null check (status in ('REQUESTED', 'VERIFIED', 'REJECTED', 'EXPIRED')),
    expires_at            timestamptz  not null,
    requested_by          uuid         not null,
    verified_size         bigint,
    verified_content_type varchar(100),
    created_at            timestamptz  not null,
    completed_at          timestamptz
);
create index upload_intents_expiry_idx on upload_intents (status, expires_at);

-- Store staff membership. Authentication (Supabase Auth) proves WHO someone is; this table says whether they are staff.
-- Rows are created only through the documented bootstrap/administrative process, never from user-editable metadata.
-- Roles / permissions are an open business decision and are intentionally NOT modelled.
create table staff_members (
    user_id    uuid        primary key,     -- identity-provider subject (JWT "sub")
    active     boolean     not null default true,
    granted_by uuid,
    granted_at timestamptz not null,
    note       text
);
