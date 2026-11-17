# Online payment with Stripe Checkout (design and API contract)

Stripe-hosted Checkout Sessions, confirmed ONLY by signed webhooks, posted into the EXISTING payment ledger. Cash at pickup is unchanged. Stripe is optional and independently disableable.
Status of this document: contract agreed before implementation; sections marked **(implemented)** are verified by tests listed in the final report.

## Findings that shape the design

1. **Payments exist only while a reservation is `READY_FOR_PICKUP`** (`PaymentServiceImpl.requireReady`; staff review comes first and stock is held). The brief's flow "submit, then pay online immediately" conflicts with that rule, and the brief forbids changing the lifecycle.
   So online payment is offered **when the reservation is READY_FOR_PICKUP** (customer tracking page, confirmation page once ready). At submission the customer only chooses/acknowledges *pay at pickup*, and is told online payment becomes available once the store marks the reservation ready.
   **Business decision needed:** allow prepayment earlier (for example while CONFIRMED)? Not implemented.
2. **HTG is not in Stripe's published supported-currency list** (docs.stripe.com/currencies, read 2026-11-17), so HTG presentment is not expected to be available. USD is. Eligibility is therefore **checked per currency, per merchant account, at runtime** and defaults to disabled: a currency is offered only if it is in `ANA_STRIPE_ENABLED_CURRENCIES` AND Stripe reports it in `country_specs/{account country}.supported_payment_currencies` for the connected account. No fallback to another currency, no conversion.
3. Amount charged = the **full current due of that currency**, calculated by the backend. Partial payment is not supported (no approved policy); overpayment is not created by this feature.
4. Money truth: a verified provider payment is ALWAYS posted to the ledger, even if the reservation moved (cancelled) or the balance was settled meanwhile (cash recorded during checkout). Such attempts are flagged `requiresAttention` for staff; nothing is refunded automatically and no refund policy is invented.

## Data (migration V14)

* `payments.payment_method` also allows `STRIPE_CHECKOUT` (the `reference` column holds the PaymentIntent id).
* `payment_attempts` — one row per Checkout Session: `id, reservation_id, currency, amount, provider ('STRIPE'), status (PENDING | PROCESSING | SUCCEEDED | FAILED | EXPIRED), provider_session_id (unique), provider_payment_intent_id, checkout_url, expires_at, payment_id, requires_attention, attention_reason, failure_code, created_at, updated_at, created_by_kind`. Partial unique index: at most ONE open (`PENDING`/`PROCESSING`) attempt per reservation and currency.
* `payment_provider_events` — `provider, event_id (unique), event_type, received_at, processed_at, outcome`: deduplication and an audit trail of every verified event.
No card data, no client secrets, no Stripe keys are stored.

## Configuration (`ana.stripe.*`, environment variables)

| Variable | Meaning |
|---|---|
| `ANA_STRIPE_ENABLED` | default `false`. When false: no session is ever created, `GET /store/payment-options` reports online payment off, webhook endpoint answers 404 |
| `STRIPE_SECRET_KEY` | backend only; `sk_test_…` locally, `sk_live_…` only in production |
| `STRIPE_WEBHOOK_SECRET` | signing secret of the webhook endpoint (`whsec_…`) |
| `ANA_STRIPE_ENABLED_CURRENCIES` | comma list, e.g. `USD`; default empty (= none) |
| `ANA_STRIPE_RETURN_BASE_URL` | web origin the customer returns to (`https://…`; `http://localhost`/`127.0.0.1` only for development). Return paths are fixed in code, so a request can never choose a redirect target |
| `ANA_STRIPE_CHECKOUT_EXPIRATION_MINUTES` | Checkout Session lifetime, 30–1440, default 30 (Stripe minimum). Expiry never cancels a reservation |

Stripe enabled without the key, the webhook secret, a valid return base URL or any currency fails the start-up guard.

## Customer API

All amounts are decimal strings per currency; HTG and USD are never combined.

### `GET /api/v1/store/payment-options` (public)
`{ "online": { "provider": "STRIPE", "currencies": ["USD"] } }` or `{ "online": null }` when Stripe is disabled or no currency is eligible. `currencies` only lists currencies that passed the eligibility check. Cached briefly server-side.

### `POST /api/v1/reservations/{reference}/payments/checkout-session`
Header `Idempotency-Key` **required**. Body `{ "currency": "USD", "phone": "…" }` (`phone` required for guests, ignored for the signed-in owner, who sends the bearer token). Authorization = the existing lookup rule: reference + phone, or the owning signed-in customer. Unknown reference, wrong phone and foreign reservation are the same `404 reservation_not_found`.
Rules, in order: Stripe enabled (`422 online_payment_unavailable`) → currency eligible (`422 currency_not_available_online`) → reservation READY_FOR_PICKUP (`409 invalid_status`) → the reservation owes that currency and it is not settled (`409 already_paid` / `422 currency_invalid`) → amount ≥ the Stripe minimum (`422 amount_below_minimum`) → rate limit (`429`).
If an open, unexpired attempt already exists for this reservation and currency with the same amount, it is **returned** (same URL, `reused: true`) instead of creating another session. The Stripe request carries a stable idempotency key derived from the internal attempt id.
`201` (or `200` when reused): `{ "attemptId": "…", "checkoutUrl": "https://checkout.stripe.com/…", "currency": "USD", "amount": "18.50", "expiresAt": "…", "reused": false }`.
Errors from Stripe are mapped to `502 payment_provider_error` without Stripe's internal text.

### `POST /api/v1/reservations/{reference}/payments/status`
Body `{ "phone": "…", "attemptId": "…"? }` (or bearer token). Returns the REAL state from the database:
```
{ "reservation": { "reference", "status", "paymentStatus": "UNPAID|PARTIAL|PAID", "balances": [{currency,total,paid,due}] },
  "attempt": { "id", "currency", "amount", "status": "PENDING|PROCESSING|SUCCEEDED|FAILED|EXPIRED", "settledAt"?, "createdAt" } | null,
  "attempts": [ …same shape, newest first, last 10… ] }
```
`attempt.status = SUCCEEDED` only after the verified webhook was posted to the ledger. Arriving on the return page proves nothing. The page polls at a gentle, backed-off rate while `PENDING|PROCESSING`.

## Webhook

`POST /api/v1/webhooks/stripe` — public, no JWT, no CSRF (signature is the authentication), raw body. `Stripe-Signature` verified with `STRIPE_WEBHOOK_SECRET` (SDK `Webhook.constructEvent`, default 5-minute tolerance). Invalid or missing signature → `400`, nothing parsed, nothing stored.
Subscribe the endpoint to: `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`, `checkout.session.expired`, `payment_intent.payment_failed`, `payment_intent.succeeded`.

Processing (one transaction, reservation row locked, at-least-once safe):
1. Insert `payment_provider_events(event_id)`; duplicate → `200` (already handled).
2. Find the attempt by session id (or `payment_intent` metadata `attemptId`). Unknown → recorded as `UNMATCHED`, `200`.
3. Verify currency and amount (`amount_total`) equal the attempt's. Mismatch → attempt flagged `requiresAttention` (`amount_mismatch`), **nothing posted**.
4. `completed` with `payment_status=paid`, or `async_payment_succeeded` → post ONCE: `Payment(method STRIPE_CHECKOUT, reference = payment_intent id)` + `PAYMENT_RECEIVED` ledger entry through `PaymentService.recordProviderPayment` (idempotency key `stripe-attempt:{attemptId}`); attempt → `SUCCEEDED`. `completed` with `payment_status=unpaid` → `PROCESSING`.
5. `async_payment_failed` → `FAILED`. `expired` → `EXPIRED` (never after `SUCCEEDED`). `payment_intent.payment_failed` → records `failure_code`, attempt stays open (the customer may retry inside the same session).
6. Out-of-order events never downgrade a terminal state; `SUCCEEDED` always wins over `FAILED`/`EXPIRED` (the money moved).
Failures to process return `500` so Stripe retries; Stripe's retries and duplicates are absorbed by steps 1 and 4.

## Staff

* `GET /api/v1/admin/payments?method=STRIPE_CHECKOUT|CASH_AT_PICKUP` — existing endpoint; `method` already filters. Stripe payments show `method: "STRIPE_CHECKOUT"` and the PaymentIntent id in `reference`.
* `GET /api/v1/admin/payment-attempts?reservationId&status&requiresAttention&page&size` — attempts with `{ id, reservationId, reservationReference, currency, amount, status, providerSessionId, providerPaymentIntentId, expiresAt, paymentId, requiresAttention, attentionReason, failureCode, createdAt, updatedAt }`. No URLs or secrets.
* `POST /admin/reservations/{id}/payments` **refuses** `method=STRIPE_CHECKOUT` (`422 method_not_allowed`): staff can never mark a Stripe payment successful.
* `POST /admin/payments/{id}/reversal` on a Stripe payment → `409 online_payment_reversal_unsupported`: reversing the ledger would not return money to the customer. A refund (Stripe Refund API) needs an approved refund policy, which does not exist, so **no refund endpoint exists**.

## Not in scope / open

Refunds and their policy · prepayment before READY_FOR_PICKUP · partial online payment · MonCash/NatCash · Stripe Dashboard branding (configured in the Dashboard, see the operations notes) · live-mode activation.
