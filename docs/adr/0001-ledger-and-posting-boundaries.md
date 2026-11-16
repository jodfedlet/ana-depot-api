# ADR 0001 — Payment ledger and posting boundaries
Status: accepted (implemented: `payments`, `ledger_entries`, `PaymentServiceImpl`, reconciliation).

**Context.** Customers pay in person, in HTG and/or USD, possibly in several instalments; staff make mistakes. We need traceable money, no silently overwritten history,
and one operational status that must not be confused with the financial record.

**Decision.**
* `Payment` rows (what was received) are immutable. Every change to what a reservation has paid is a **`ledger_entries` row** (signed amount in its own currency):
  `PAYMENT_RECEIVED`, `PAYMENT_REVERSAL` (references and cancels exactly one earlier entry, reason mandatory, same currency, at most once — unique), `ADJUSTMENT` (reason mandatory).
  Corrections never edit history. `UPDATE`/`DELETE` on `payments` and `ledger_entries` are refused by a database trigger (not just by convention).
* The paid amount on `reservation_totals` is a **derived cache**, updated in the same transaction and reconcilable (`PaymentService.reconcile`); the ledger is authoritative. We never use a mutable balance as the sole truth.
* Entries keep actor, timestamp, correlation id and the originating payment; payment + ledger + cache + audit + outbox event commit atomically with the reservation row locked.
* **Posting boundary.** Ledger entries are written for: money received, reversed, adjusted. **Not** for: reservation status changes, stock movements, catalog/price changes, notes (these are audited, not financial). Nothing artificial is posted.
* Reservation status (`PENDING … COLLECTED`) and payment status (`UNPAID/PARTIAL/PAID`, derived) are separate; collection requires PAID in every currency.
* **Double-entry accounting is not introduced**: there is no chart of accounts or tax logic and none was requested. This is a single-sided payments journal. When accounting postings are added they must be balanced journal entries (Σdebits = Σcredits per currency) with a source reference to the ledger entry.

**Alternatives.** (a) mutable `paid_amount` only — loses history, can't reconcile; (b) full double-entry now — invents accounts/tax rules we don't have; (c) event sourcing — heavy for a single store.

**Consequences.** Cheap, auditable, correct per currency. Overpayment is still accepted (undecided policy). Reversal/adjustment are limited to READY_FOR_PICKUP (conservative; revisit when the owner decides about post-collection corrections). Reports can sum ledger entries per currency without any conversion.
