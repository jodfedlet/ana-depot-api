# ADR 0002 — Transactional outbox in PostgreSQL
Status: accepted (implemented: `outbox_events`, `OutboxEventRecorder`, `OutboxDispatcher`).

**Context.** Reservation/payment/inventory changes must be able to trigger asynchronous work (notifications later, projections) without ever publishing before/after a commit "and hoping".

**Decision.**
* Services call `DomainEventRecorder.record(...)`; the infra implementation inserts into `outbox_events` in the **caller's transaction** (`@Transactional(MANDATORY)` — it refuses to run outside one). If the business change rolls back, the event never existed (tested).
* Row: stable event id (UUID), type, aggregate type/id/**version**, payload (jsonb, **no personal data**: consumers load the aggregate by id), correlation id, status `PENDING|PROCESSING|PROCESSED|DEAD`, attempts, `next_attempt_at`, `lease_until`, `last_error`, `processed_at`.
* **Dispatcher** (scheduled, any number of instances): three short transactions per batch and none while a handler runs — (1) *claim*: `SELECT … FOR UPDATE SKIP LOCKED`, mark PROCESSING with a lease; (2) *deliver*; (3) *settle*. A dispatcher that dies leaves an expired lease and the event is claimed again (tested). Concurrent dispatchers never claim one row twice (tested).
* **Ordering**: global `seq`; an event is held back while an earlier event of the same aggregate is PENDING/PROCESSING, and a failure stops later events of that aggregate in the batch (tested). A DEAD event does not block the aggregate forever (it needs a human; consumers use `aggregateVersion` to notice gaps).
* **Failure handling**: retries with exponential backoff + jitter (ADR 0005) up to `ana.outbox.max-attempts`, then `DEAD` (terminal, visible via the `anadepot.outbox.dead` gauge and `last_error`); a `PermanentFailure` goes straight to DEAD. Processed rows are purged after `processed-retention`.
* No Kafka/RabbitMQ: a broker would add an operational burden with no demonstrated need. The recorder/handler interfaces make that swap local to `infra.outbox` if one appears.

**Consequences.** Reliable, restart-safe, observable (`anadepot.outbox.pending|dead`, `anadepot.outbox.events{result}`), at-least-once. The only consumer today is a logging handler; no notification channel exists (undecided).
