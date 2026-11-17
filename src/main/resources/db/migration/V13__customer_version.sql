-- Optimistic concurrency for staff edits of a customer's contact details (the staff API requires the version it read).
alter table customers add column version bigint not null default 0;
