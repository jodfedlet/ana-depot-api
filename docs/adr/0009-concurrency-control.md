# ADR 0009 — Concurrency control
Status: accepted (implemented and tested with real concurrent transactions).

**Decision.** *Pessimistic row locks inside short use-case transactions, plus unique constraints and optimistic versions as second lines of defence.*
* **Stock**: `SELECT … FOR UPDATE` on `inventory_items` for every item a reservation touches, **in a stable order** (`ItemKey`: product id then variant id) so concurrent multi-product reservations cannot deadlock (tested: opposite-order lists, 16 threads). Because stock is shared across currencies, an HTG and a USD reservation of the same product contend on the **same row** (tested: 20 customers, 5 units, mixed currencies → exactly 5 succeed, `reserved` equals the open reservations' quantity).
* **Reservation transitions and payments** lock the reservation row; the loser re-reads the new state and gets `invalid_status`/`already_paid` (tested). Two full payments racing → exactly one applies.
* **Idempotency/dedup/rate limit** use unique constraints as arbiters (ADR 0004/0006/0008).
* **Optimistic `@Version`** on products, categories, reservations, inventory items catches lost updates in admin edits → 409 `concurrent_update`.
* Transactions stay short; no network call inside one.
**Why pessimistic for stock:** the last-unit contention is real and cheap to serialise; optimistic retries under contention would make customers see spurious failures. Contention is per product, so unrelated products don't block each other.
**Alternatives:** serializable isolation (retry storms, hard to test), a conditional `UPDATE … SET reserved = reserved + n WHERE on_hand - reserved >= n` (also valid; the row lock was chosen because the domain object enforces the rule and several rows must be checked atomically).
**Not claimed:** overselling is prevented *by these server-side mechanisms*, not by the frontend's validation.
