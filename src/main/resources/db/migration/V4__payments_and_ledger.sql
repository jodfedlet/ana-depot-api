-- Part 4: payments and the payment ledger.
-- payments: money received, immutable. ledger_entries: append-only journal of every change to what was paid
-- (received / reversal / adjustment). A reservation's paid amount per currency = SUM(ledger_entries.amount).

create table payments (
    id             uuid          primary key,
    reservation_id uuid          not null references reservations (id),
    currency       varchar(3)       not null,
    amount         numeric(19,2) not null check (amount > 0),
    payment_method varchar(20)   not null check (payment_method in ('CASH_AT_PICKUP')),
    received_at    timestamptz   not null,
    recorded_at    timestamptz   not null,
    recorded_by    uuid,                                        -- identity-provider user id
    reference      text,
    note           text,
    -- a payment can only be in a currency the reservation actually owes
    foreign key (reservation_id, currency) references reservation_totals (reservation_id, currency)
);
create index payments_reservation_idx on payments (reservation_id, recorded_at);
create trigger payments_immutable before update or delete on payments for each row execute function prevent_mutation();

create table ledger_entries (
    id                uuid          primary key,
    reservation_id    uuid          not null references reservations (id),
    payment_id        uuid          references payments (id),
    entry_type        varchar(20)   not null check (entry_type in ('PAYMENT_RECEIVED', 'PAYMENT_REVERSAL', 'ADJUSTMENT')),
    currency          varchar(3)       not null,
    amount            numeric(19,2) not null check (amount <> 0),   -- signed
    reverses_entry_id uuid unique,                                  -- an entry can be reversed at most once
    reason            text,
    created_at        timestamptz   not null,
    created_by        uuid,
    correlation_id    varvarchar(64),
    foreign key (reservation_id, currency) references reservation_totals (reservation_id, currency),
    unique (id, currency),
    foreign key (reverses_entry_id, currency) references ledger_entries (id, currency),   -- a reversal is in the same currency
    check (entry_type <> 'PAYMENT_RECEIVED' or (amount > 0 and payment_id is not null)),
    check (entry_type <> 'PAYMENT_REVERSAL' or (amount < 0 and reverses_entry_id is not null and payment_id is not null)),
    check (entry_type <> 'ADJUSTMENT' or (reason is not null and length(btrim(reason)) > 0))
);
create unique index ledger_one_receipt_per_payment on ledger_entries (payment_id) where entry_type = 'PAYMENT_RECEIVED';
create index ledger_reservation_idx on ledger_entries (reservation_id, created_at);
create trigger ledger_entries_immutable before update or delete on ledger_entries for each row execute function prevent_mutation();

-- Not enforceable here: a reversal's amount equals the negated original amount; reservation_totals.paid_amount equals the
-- ledger sum (cache -> reconciled by PaymentService.reconcile and tests).
