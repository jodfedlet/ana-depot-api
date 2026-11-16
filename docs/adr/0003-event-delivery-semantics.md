# ADR 0003 — Event delivery semantics: at-least-once, idempotent consumers
Status: accepted.

**Decision.** Outbox delivery is **at-least-once**. We do **not** claim exactly-once. Duplicates arise from a crash between "handler finished" and "row marked PROCESSED", from lease expiry, or from provider redelivery.
Consumers must be idempotent; the dispatcher helps by calling every handler through `EventDeduplicator.runOnce(handlerName, eventId, effect)` (ADR 0006), so an in-database effect runs once per (consumer, event id) even when the event is delivered again (tested).
Effects outside the database (HTTP calls, e-mail) are still at-least-once and must carry their own idempotency key (the event id is the natural one).

**Why it fits.** The business effects of our events (notifying a customer, updating a read model) tolerate a rare duplicate if the consumer dedupes by event id; paying for exactly-once infrastructure is not justified.
