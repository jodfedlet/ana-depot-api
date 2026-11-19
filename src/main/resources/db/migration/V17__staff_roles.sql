-- Staff roles (D14, owner-approved): three fixed roles, no custom roles. Authorization stays a server-side lookup of this row; token claims are never used.
--   OWNER   everything, including team and store settings
--   MANAGER reservations, payments (incl. reversal/adjustment), inventory adjustments, catalog, customers, reports; no team, no store settings
--   STAFF   counter work: reservations, cash payments, stock and customers read-only, no prices, no reports, no team
-- Every existing member becomes OWNER so nobody loses access when this migration runs (the column default does that for the rows already present).
alter table staff_members add column role varchar(16) not null default 'OWNER' check (role in ('OWNER', 'MANAGER', 'STAFF'));

-- An invitation carries the role the invitee will receive. Invitations issued before this migration (all-or-nothing era) get the least privilege.
alter table staff_invitations add column role varchar(16) not null default 'STAFF' check (role in ('OWNER', 'MANAGER', 'STAFF'));
