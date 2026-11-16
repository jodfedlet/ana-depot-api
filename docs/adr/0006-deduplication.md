# ADR 0006 — Deduplication of events and webhooks
Status: accepted (implemented: `processed_events`, `EventDeduplicator`).

**Decision.** Messages are identified by a stable id (outbox event id; a provider's event id for webhooks). `EventDeduplicator.runOnce(consumer, eventId, effect)` runs, in **one transaction**, "record (consumer, eventId) + run the effect", guarded by `unique(consumer, event_id)`.
Second delivery → the effect is **skipped**, `duplicate_count`/`last_duplicate_at` are updated and `anadepot.dedup{result=duplicate}` is incremented (duplicates remain observable). Simultaneous duplicates → exactly one effect (tested with 8 threads). If the effect fails, the mark rolls back, so the event can be retried.
A conflicting repeat (same id, different body) is not special-cased yet: the first processed version wins and the repeat is counted; a payload hash column can be added when a real provider requires conflict detection.
**Webhooks:** no provider exists, so no webhook endpoint was built (the rate-limit operation `webhook.receive` is reserved). Verified behavior is the primitive above; when a provider is chosen its endpoint must verify the signature, then call `runOnce(provider, event.id, …)` and respond 2xx for duplicates.
