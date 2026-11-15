-- Part 2: customers and shared inventory (ONE stock position per product/variant, whatever the currency).

create table customers (
    id           uuid         primary key,
    first_name   varchar(100) not null,
    last_name    varchar(100) not null,
    dial_code    varchar(8)   not null,
    phone        varchar(32)  not null check (phone ~ '^[0-9]{6,}$'),   -- digits only
    email        varchar(254),
    auth_user_id uuid unique,                                           -- identity-provider subject, once the person signs in
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    unique (dial_code, phone)
);

create table inventory_items (
    id                  uuid        primary key,
    product_id          uuid        not null references products (id),
    variant_id          uuid,
    tracked             boolean     not null default true,
    low_stock_threshold integer check (low_stock_threshold >= 0),
    on_hand             integer     not null default 0 check (on_hand >= 0),
    reserved            integer     not null default 0 check (reserved >= 0),   -- may exceed on_hand after physical loss; available = max(0, on_hand - reserved)
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    version             bigint      not null default 0,
    foreign key (variant_id, product_id) references product_variants (id, product_id),
    check (tracked or (on_hand = 0 and reserved = 0))
);
create unique index inventory_items_product_uq on inventory_items (product_id) where variant_id is null;
create unique index inventory_items_variant_uq on inventory_items (variant_id) where variant_id is not null;

create table inventory_movements (
    id                uuid        primary key,
    inventory_item_id uuid        not null references inventory_items (id),
    movement_type     varchar(10) not null check (movement_type in ('INITIAL', 'ADD', 'REDUCE', 'RECOUNT', 'SALE')),
    delta             integer     not null,
    balance_after     integer     not null check (balance_after >= 0),
    reason            varchar(20) not null,                             -- no CHECK: the reason list may grow
    reservation_id    uuid,                                             -- foreign key added with the reservations table
    note              text,
    created_at        timestamptz not null,
    recorded_by       uuid,
    check ((movement_type = 'SALE') = (reservation_id is not null))
);
create index inventory_movements_item_idx on inventory_movements (inventory_item_id, created_at);

-- Append-only guard shared by the audit-grade tables (movements, payments, ledger, audit trail).
create function prevent_mutation() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only: % is not allowed', tg_table_name, tg_op using errcode = 'integrity_constraint_violation';
end;
$$;
create trigger inventory_movements_immutable before update or delete on inventory_movements
    for each row execute function prevent_mutation();
