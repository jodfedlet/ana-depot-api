-- Customer accounts: registered customers are separate records from guests.
--
-- Before: unique (dial_code, phone) on every customer, so a guest and a signed-in user with the same phone number would have collided
-- (and a guest row could be silently "claimed" by whoever signed in with that number).
-- After:  - a GUEST row (auth_user_id is null) is unique per phone number (a repeat guest reservation reuses it);
--         - a REGISTERED row is owned by exactly one identity-provider user (auth_user_id stays unique, from V2) and may share a phone number
--           with a guest row or another registered row: phone numbers prove nothing about who owns an account;
--         - guests are never merged into accounts automatically.
-- Existing data is preserved: every existing row had a unique phone, so the new partial unique index is satisfied by all of them.

alter table customers drop constraint if exists customers_dial_code_phone_key;

create unique index customers_guest_phone_uq on customers (dial_code, phone) where auth_user_id is null;

-- the account's reservation list (newest first) is read by customer
create index reservations_customer_created_idx on reservations (customer_id, created_at desc, id);
drop index if exists reservations_customer_idx;
