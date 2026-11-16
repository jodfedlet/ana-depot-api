# ADR 0004 — Idempotency storage and retention
Status: accepted (implemented: `idempotency_keys`, `JpaIdempotencyService`).

**Context.** Clients (a flaky phone connection, a double tap, a proxy retry) will resend reservation submissions, payments and stock adjustments. A retry must never duplicate a reservation, a payment or a stock change.

**Decision.** Server-side, in PostgreSQL, atomic with the business change:
1. look the key up (scope = actor + operation + key); found → same payload fingerprint ⇒ return the stored resource id (**original result**); different fingerprint ⇒ `idempotency_key_reuse` (409);
2. not found → **one transaction** inserts the key, runs the work and stores the resource id. The unique constraint `(actor_scope, operation, idempotency_key)` arbitrates races: a concurrent twin blocks on the insert until we commit, then fails on the constraint; its transaction (and work) rolls back and it re-reads our committed result (tested with 8 simultaneous identical submissions → one reservation, stock held once).
* A failed operation rolls the key back with it, so the key is not "burned" (tested).
* Fingerprint = SHA-256 of the canonical request parts (`Fingerprint.of`).
* **Required** for financial and stock writes (payment, reversal, adjustment, stock adjustment) — the service refuses them without a key; recommended for reservation submit/transitions/uploads.
* **Retention**: `ana.idempotency.retention` (default 7 days) via `expires_at`; a scheduled cleanup deletes expired keys and is idempotent. After retention a replay with the same key is treated as new — a deliberate trade-off: clients retry within seconds/minutes, not weeks.
* Guests are scoped `anonymous`; the fingerprint contains the contact details, so a different guest cannot replay another's key.

**Alternatives.** In-memory/Redis cache (lost on restart, not shared) — rejected; client-only dedup — rejected (must be server-side).
