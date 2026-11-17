-- Online payment (Stripe Checkout). The payment LEDGER stays the only record of money: a verified provider payment is a normal payments row
-- (method STRIPE_CHECKOUT, reference = the provider's payment id) plus a PAYMENT_RECEIVED ledger entry. These two tables only track the attempt
-- (one Checkout Session) and the provider events already handled. No card data, no client secrets and no API keys are stored.

alter table payments drop constraint payments_payment_method_check;
alter table payments add constraint payments_payment_method_check check (payment_method in ('CASH_AT_PICKUP', 'STRIPE_CHECKOUT'));

create table payment_attempts (
    id                         uuid          primary key,
    reservation_id             uuid          not null references reservations (id),
    currency                   varchar(3)    not null,
    amount                     numeric(19,2) not null check (amount > 0),
    provider                   varchar(20)   not null check (provider in ('STRIPE')),
    status                     varchar(20)   not null check (status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    provider_session_id        varchar(255),
    provider_payment_intent_id varchar(255),
    checkout_url               text,
    expires_at                 timestamptz,
    payment_id                 uuid references payments (id),
    requires_attention         boolean       not null default false,
    attention_reason           varchar(100),
    failure_code               varchar(100),
    created_by_user_id         uuid,
    created_at                 timestamptz   not null,
    updated_at                 timestamptz   not null,
    foreign key (reservation_id, currency) references reservation_totals (reservation_id, currency),
    check (status <> 'SUCCEEDED' or payment_id is not null)
);
create unique index payment_attempts_session_uq on payment_attempts (provider_session_id) where provider_session_id is not null;
-- at most one OPEN attempt per reservation and currency: a double click or a retry can never open a second session
create unique index payment_attempts_one_open on payment_attempts (reservation_id, currency) where status in ('PENDING', 'PROCESSING');
create unique index payment_attempts_payment_uq on payment_attempts (payment_id) where payment_id is not null;
create index payment_attempts_reservation_idx on payment_attempts (reservation_id, created_at);
create index payment_attempts_attention_idx on payment_attempts (created_at) where requires_attention;
alter table payment_attempts enable row level security;

create table payment_provider_events (
    id           uuid         primary key,
    provider     varchar(20)  not null,
    event_id     varchar(255) not null,
    event_type   varchar(100) not null,
    attempt_id   uuid references payment_attempts (id),
    outcome      varchar(40)  not null,
    received_at  timestamptz  not null,
    unique (provider, event_id)
);
alter table payment_provider_events enable row level security;
