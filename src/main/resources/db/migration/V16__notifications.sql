-- Transactional e-mail notifications (docs/notifications.md). One row per customer message, written IN THE SAME TRANSACTION as the business operation that caused it
-- (reservation transition, recorded payment, verified provider event); the message is built and sent LATER by a dispatcher, never inside a business transaction.
-- The row holds REFERENCES (reservation, payment, attempt), never the message body and never the recipient address: the address is read at send time from the reservation's contact
-- snapshot. `recipient_hint` is a masked form for the staff screen and `recipient_hash` a one-way hash used only to throttle messages per address.

create table notifications (
    id              uuid         primary key,
    event_id        uuid         not null unique,                 -- unique event identifier (also the correlation id in the message headers)
    dedupe_key      varchar(200) not null unique,                 -- one message per business fact, whatever the number of duplicate deliveries upstream
    type            varchar(40)  not null,
    aggregate_type  varchar(30)  not null,
    aggregate_id    uuid         not null,
    reservation_id  uuid         not null references reservations (id),
    payment_id      uuid,                                          -- template context: the recorded payment (payment messages)
    attempt_id      uuid,                                          -- template context: the checkout attempt (failed / expired messages)
    locale          varchar(8)   not null,
    recipient_kind  varchar(30)  not null check (recipient_kind in ('RESERVATION_CONTACT')),
    recipient_hint  varchar(120),
    recipient_hash  varchar(64),
    status          varchar(30)  not null check (status in ('PENDING', 'PROCESSING', 'RETRY', 'SENT', 'DEAD', 'CAPTURED',
                                                            'SKIPPED_NO_RECIPIENT', 'SKIPPED_INVALID_RECIPIENT', 'SKIPPED_THROTTLED')),
    attempts        int          not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz  not null,
    lease_until     timestamptz,
    last_error_code varchar(60),
    last_error_at   timestamptz,
    sent_at         timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    check ((status = 'SENT') = (sent_at is not null) or status = 'CAPTURED'),
    check (status <> 'PROCESSING' or lease_until is not null)
);
create index notifications_due_idx on notifications (next_attempt_at) where status in ('PENDING', 'RETRY', 'PROCESSING');
create index notifications_reservation_idx on notifications (reservation_id, created_at);
create index notifications_status_idx on notifications (status, created_at desc);
create index notifications_recipient_idx on notifications (recipient_hash, created_at) where recipient_hash is not null;
alter table notifications enable row level security;
