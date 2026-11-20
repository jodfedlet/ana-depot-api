-- Notification outcomes that are NOT failures, and a way to resend an old message (review of the 72-hour rule and the state-change suppression, docs/notifications.md).
--   SUPERSEDED  the message would no longer be true (e.g. "ready for pickup" after the reservation was collected): nothing to fix, nobody to tell.
--   EXPIRED     the message waited longer than its limit and was not sent. For an important message the customer may never have been told: staff are warned and can resend it.
-- Before this migration both were stored as DEAD with last_error_code 'state_changed' / 'stale'; they are converted so the failure counters mean what they say.
alter table notifications drop constraint notifications_status_check;
alter table notifications add constraint notifications_status_check check (status in ('PENDING', 'PROCESSING', 'RETRY', 'SENT', 'DEAD', 'CAPTURED',
        'SKIPPED_NO_RECIPIENT', 'SKIPPED_INVALID_RECIPIENT', 'SKIPPED_THROTTLED', 'SUPERSEDED', 'EXPIRED'));

-- A resent message starts a new age: its limit is measured from the last time staff put it back in the queue, never only from its creation.
alter table notifications add column revived_at timestamptz;

update notifications set status = 'SUPERSEDED' where status = 'DEAD' and last_error_code = 'state_changed';
update notifications set status = 'EXPIRED' where status = 'DEAD' and last_error_code = 'stale';
