-- Terms acknowledgement: when the customer accepted the reservation terms and WHICH version of the terms was in force.
-- The legal text itself is not stored here (it is supplied separately by the business); only the version label configured in
-- `ana.legal.terms-version` and the acceptance time. Existing reservations were created before this was recorded: both columns stay NULL
-- (no value is invented for them). A version without an acceptance time is meaningless, hence the check.
alter table reservations add column terms_version varchar(64);
alter table reservations add column terms_accepted_at timestamptz;
alter table reservations add constraint reservations_terms_pair check (terms_version is null or terms_accepted_at is not null);
