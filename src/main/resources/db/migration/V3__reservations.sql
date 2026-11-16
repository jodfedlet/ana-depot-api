-- Part 3: reservations. Lines are SNAPSHOTS (name, unit, price, currency, tier): they never follow later product changes.

create table reservations (
    id                 uuid        primary key,
    reference          varchar(20) not null unique check (reference ~ '^ANA-[A-Z2-9]{6}$'),
    status             varchar(20) not null check (status in ('PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'READY_FOR_PICKUP', 'COLLECTED', 'CANCELLED', 'EXPIRED')),
    locale             varchar(8)  not null,
    customer_id        uuid references customers (id),
    contact_first_name varchar(100) not null,
    contact_last_name  varchar(100) not null,
    contact_dial_code  varchar(8)   not null,
    contact_phone      varchar(32)  not null,
    contact_email      varchar(254),
    pickup_snapshot    jsonb       not null check (jsonb_typeof(pickup_snapshot) = 'object'),  -- pickup details as promised to the customer
    ready_at           timestamptz,
    expires_at         timestamptz,                                      -- stays null: no hold period has been decided
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null default 0,
    check (status not in ('READY_FOR_PICKUP', 'COLLECTED') or ready_at is not null)
);
create index reservations_status_idx on reservations (status, created_at desc);
create index reservations_customer_idx on reservations (customer_id);

-- One row per currency present on the reservation. paid_amount is a CACHE of the payment ledger (see V4) and can be reconciled.
create table reservation_totals (
    id             uuid          primary key,
    reservation_id uuid          not null references reservations (id) on delete cascade,
    currency       varchar(3)       not null references currencies (code),
    total_amount   numeric(19,2) not null check (total_amount > 0),
    paid_amount    numeric(19,2) not null default 0 check (paid_amount >= 0),
    unique (reservation_id, currency)
);

create table reservation_lines (
    id               uuid          primary key,
    reservation_id   uuid          not null references reservations (id) on delete cascade,
    position         integer       not null check (position >= 0),
    product_id       uuid          not null references products (id),
    variant_id       uuid,
    product_name     text          not null,
    product_sku      varchar(64),
    variant_label    text,
    selling_unit     varchar(20)   not null,
    quantity         integer       not null check (quantity > 0),
    currency         varchar(3)       not null,
    unit_price       numeric(19,2) not null check (unit_price > 0),
    pricing_kind     varchar(10)   not null check (pricing_kind in ('RETAIL', 'SALE', 'WHOLESALE')),
    tier_min_quantity integer check (tier_min_quantity >= 2),
    line_total       numeric(19,2) not null,
    foreign key (variant_id, product_id) references product_variants (id, product_id),
    -- the line's currency must be one of the reservation's total currencies (deferred: totals and lines are inserted together)
    foreign key (reservation_id, currency) references reservation_totals (reservation_id, currency) deferrable initially deferred,
    unique (reservation_id, position),
    check (line_total = unit_price * quantity),
    check ((pricing_kind = 'WHOLESALE') = (tier_min_quantity is not null))
);
create index reservation_lines_product_idx on reservation_lines (product_id);

create table reservation_status_history (
    id             uuid        primary key,
    reservation_id uuid        not null references reservations (id) on delete cascade,
    seq            integer     not null check (seq >= 1),
    from_status    varchar(20),
    to_status      varchar(20) not null,
    reason_code    varchar(40),
    reason_note    text,
    changed_at     timestamptz not null,
    actor_kind     varchar(10) not null check (actor_kind in ('CUSTOMER', 'STAFF', 'SYSTEM')),
    actor_user_id  uuid,
    unique (reservation_id, seq)
);

create table reservation_notes (
    id             uuid        primary key,
    reservation_id uuid        not null references reservations (id) on delete cascade,
    visibility     varchar(16) not null check (visibility in ('INTERNAL', 'CUSTOMER_VISIBLE')),
    body           text        not null check (length(btrim(body)) > 0),
    author_kind    varchar(10) not null check (author_kind in ('CUSTOMER', 'STAFF', 'SYSTEM')),
    author_user_id uuid,
    created_at     timestamptz not null
);
create index reservation_notes_idx on reservation_notes (reservation_id, created_at);

alter table inventory_movements add constraint inventory_movements_reservation_fk foreign key (reservation_id) references reservations (id);

-- Not enforceable here: reservation_totals equal the per-currency sum of the lines (checked by the domain and by tests).
