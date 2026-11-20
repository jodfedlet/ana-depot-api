# Decisions the owner must make

Nothing below is implemented as a business rule unless it says so. Where the software has to do *something* today, that **current behaviour** is stated so nobody is surprised. Each decision has: why it matters, what the software does now, a **recommended default**, what changing it costs technically, and the question to answer. Reply with the decision number and your choice (for example "D3: option A").

Written for: the shop owner (no technical knowledge assumed). Technical readers: `docs/domain.md`, `docs/payments-stripe.md`, `docs/auth.md`.

---

## A. Money

### D1. Refunds
- **STATUS 2026-11-19: APPROVED (dashboard refunds + flag) and IMPLEMENTED.** Staff see a per-currency "refund due" figure on any rejected/cancelled/expired reservation with card money; refunds the owner makes in the Stripe dashboard are observed from verified `charge.refunded` events (cumulative, capped, audited; wrong currency or unpaid attempts are flagged) and clear the figure by themselves. The ledger is not netted. Still open for a later in-application refund: who may refund, partial/full, fee handling, and accounting of refunds in reports. Cash received on an expired reservation is not flagged (not part of the approval) - tell us if you want it.
- **Why it matters:** if a customer paid online and the store cannot fill the order, they expect their money back. Today there is no way to give it back inside the system.
- **What happens now:** a staff member cannot undo an online (Stripe) payment in the application: the system refuses, because undoing it in our books would **not** return the money to the customer's card. A cash payment can be reversed in the books (staff only, while the reservation is ready for pickup).
- **Recommended default:** refunds are done by the owner in the Stripe dashboard (no code), and a staff member records a note on the reservation. When you want it inside the application, we add a "Refund" action that calls Stripe, is limited to the owner, needs a reason, and writes a refund line in the ledger only after Stripe confirms.
- **Technical consequence of "inside the application":** a new refund flow (Stripe refund API, a webhook for refund events, a new ledger entry type, reports, customer wording). About a week of work plus tests.
- **Question:** who may refund, how long after payment, full or partial, and who pays any fee Stripe keeps?
- **Recommendation (precise):** (1) launch with refunds done in the Stripe dashboard; the application only shows a "refund due" flag on a cancelled/rejected reservation that has a card payment, so nothing is forgotten (small: a derived flag in the admin reservation view, no money movement). (2) Later, in-application refunds: full or partial, only by an Owner (D14), always in the payment's own currency, never more than that payment, reason mandatory, `Idempotency-Key` required, the ledger entry written **only after Stripe confirms** (webhook `refund.updated` / `charge.refunded`), Stripe's processing fee is not returned (state this in the screen).
- **Affected components if built in the application:**
  - *Database:* new migration: table `payment_refunds` (id, payment_id, currency, amount, status PENDING/SUCCEEDED/FAILED, provider_refund_id, reason code, requested_by, created_at; RLS; unique provider_refund_id); extend the ledger entry-type check with `REFUND`; no edit to released migrations.
  - *Backend core:* `Refund` model, `PaymentService.refund(...)`, `CheckoutGateway.refund(paymentIntentId, amount, idempotencyKey)`, reconciliation and `Reservation.balances` aware of refunds; replaces the `online_payment_reversal_unsupported` refusal for card payments (cash keeps the existing reversal).
  - *Backend infra:* `StripeCheckoutGateway.refund`, webhook handling of refund events (signature, dedupe, out-of-order: SUCCEEDED wins, as for payments), `AdminPaymentController` endpoint, permissions (D14), rate limit, audit.
  - *Reports:* payments report gains a `refunded` figure per currency and method; sales/net figures stay per currency.
  - *Frontend:* admin payment panel "Refund" action (confirm dialog, reason, amount ≤ refundable), refund badges/history, customer `PaymentLine` shows refunded amount (fr+ht), reservation detail "refund due" flag.
  - *Tests:* refund success/failure/duplicate/out-of-order webhooks, over-refund refused, currency isolation, idempotency replay, reconciliation, permission matrix, customer view privacy.
  - *Changes to approved behaviour:* none of the existing protected regression tests change; the card-payment-reversal refusal test (`OnlinePaymentIntegrationTest.staffCannotRecordOrReverse...`) is replaced by the refund tests only with your approval.

### D2. Partial payments
- **STATUS: APPROVED (recommendation) and in force.** Cash partial payments work; online stays the full remaining amount of one currency.
- **Why:** customers sometimes want to pay half now and half at pickup.
- **Now:** cash: the store may record any amount up to and beyond the balance (see D3). Online: the customer can only pay the **full remaining amount of one currency**; there is no "pay half" online.
- **Recommended default:** cash partial payments allowed (already works); online stays full-amount only. A reservation is "paid" only when every currency is fully settled.
- **Consequence of allowing partial online payments:** the customer chooses an amount, the backend validates it against the balance, and the same "one open attempt per currency" rule applies. Small change, but the customer screens need an amount field.
- **Question:** do you want partial online payments? Is there a minimum?
- **Recommendation (precise):** keep online payment **full remaining amount of one currency**; allow partial payment **in cash only** (it already works). Partial online payment multiplies cases (several open sessions' history, refunds of parts, "who owes what") for little benefit when pickup is in person.
- **Affected components if you choose partial online payment:**
  - *Backend:* `OnlinePaymentService.createCheckout` accepts an optional `amount` (> 0, at most the current due, at least the provider minimum; USD 0.50) instead of always the due; the attempt already stores its own amount and the webhook already compares the paid amount with the attempt's amount, so integrity checks stay; the "one open attempt per reservation and currency" index stays; `Reservation.paymentStatus` already yields PARTIAL; collection still requires every currency settled.
  - *Database:* none (`payment_attempts.amount` exists).
  - *Frontend:* amount field in `features/payments/online-pay-panel.tsx` (per currency, never converted), validation and messages fr+ht, the status page shows the remaining due after each payment.
  - *Tests:* partial then remainder, two partials, minimum, amount above due refused, concurrent partial attempts, webhook mismatch, currency isolation.

### D3. Overpayments
- **STATUS: APPROVED (recommendation) and IMPLEMENTED.** A payment above the remaining balance of its currency is refused (`422 overpayment_not_allowed`); the payment dialog is pre-filled with the amount due and states the maximum. The protected overpayment regression test and the 6-way concurrent-payment test were changed with your explicit approval (they now assert the approved rule; the concurrent test makes six simultaneous payments of 5,000 against 25,000 settle exactly five).
- **Why:** a customer hands over more cash than owed.
- **Now:** the store may record it; the amount due never goes below zero and the original amount is kept. Nothing says what happens to the extra. Online, overpayment cannot occur (the amount is fixed by the system).
- **Recommended default:** refuse a cash payment larger than the remaining balance and tell the staff member to give change. This removes the ambiguity entirely.
- **Consequence:** a one-line rule in the payment use case plus a clear message in both languages; existing overpayment tests are updated by approval.
- **Question:** refuse, or accept and treat the extra as a credit? (Credit needs an accounting policy.)
- **Recommendation (precise):** refuse a cash payment greater than the remaining balance of that currency (`422 overpayment_not_allowed`), with the maximum shown in the dialog (staff give change). The recording dialog is pre-filled with the amount due.
- **Affected components:** *Backend:* one rule in `PaymentServiceImpl.record` (core) + error code and fr/ht message; *Database:* none; *Frontend:* `features/admin/payment-panel.tsx` (pre-fill, maximum, message), `apiErrors` fr+ht; *Docs:* `domain.md`; *Tests:* **protected regression tests** `AdminPaymentApiIntegrationTest` / `PaymentLedgerIntegrationTest` currently assert that an overpayment is accepted (documented behaviour); changing them is a business-behaviour change that needs your explicit approval, as required by the repository rules. Reports and reconciliation are unaffected.

### D4. Online payment before the order is ready
- **STATUS: APPROVED (recommendation) and in force.** Online payment opens only when the reservation is ready for pickup.
- **Why:** paying earlier reduces no-shows; paying too early risks paying for something the store later cannot supply.
- **Now:** online payment opens only when the reservation is **ready for pickup**.
- **Recommended default:** keep it (the customer pays only for stock the store has confirmed and set aside).
- **Consequence of opening earlier (for example when "confirmed"):** a rejected or cancelled reservation could already be paid, which makes refunds (D1) mandatory first.
- **Question:** keep "after ready", or allow payment once the store has confirmed?
- **Recommendation (precise):** keep **after ready** until refunds (D1) are available in the application. Then, if the shop wants prepayment, allow it from **confirmed** (the store has accepted and the stock is held), behind a setting, never from pending review.
- **Affected components if prepayment from confirmed:** *Backend:* the eligibility rule in `OnlinePaymentServiceImpl.createCheckout` (currently READY_FOR_PICKUP) becomes configurable (`ana.stripe.prepay-from=READY|CONFIRMED`); rejecting or cancelling a reservation that has card payments must raise the "refund due" flag (D1) and the webhook-after-cancel path (already flagged `requires_attention`) becomes a normal case; `PaymentServiceImpl` (cash) keeps its own READY rule unless you also change it. *Database:* none. *Frontend:* `lib/payments/online.ts` (`acceptsOnlinePayment`, `announcesOnlinePayment`), `online-pay-panel.tsx`, confirmation/tracking wording fr+ht, the card option description on the reservation step. *Tests:* checkout from confirmed, refused from pending, reject/cancel after payment (flag, stock release, ledger intact), webhook arriving after cancel.

### D5. Currency and fees on online payments (HTG, USD)
- **Why:** it depends on the **Stripe account** you create (merchant country, which currencies it can charge and settle in, fees). This cannot be decided in software.
- **Now:** online payment is **off** until the Ana Dépôt merchant account exists. The code offers a currency only if that account can charge it; it never converts HTG to USD.
- **Question:** in which country will the Ana Dépôt merchant account be created, and do you accept the fees and the settlement currency Stripe shows for it?

## B. Reservations

### D6. Reservation hold period (automatic expiry)
- **STATUS: APPROVED (3 days after ready, reminder 24 h before) and IMPLEMENTED.** The store's own number of days (pickup settings) wins; without one, 3 days. The deadline is fixed when the reservation becomes ready. A background job (every 5 min, safe on several instances) queues the reminder and expires overdue ready reservations: stock released, customer e-mailed, money stays on the ledger, card money shows "refund due". Configuration: `ANA_RESERVATION_HOLD_DAYS`, `ANA_RESERVATION_REMINDER_LEAD`, `ANA_RESERVATION_EXPIRY_INTERVAL`, `ANA_RESERVATION_EXPIRY_ENABLED`.
- **Why:** reserved stock is unavailable to others; without an expiry a customer who never comes keeps the stock blocked.
- **Now:** **nothing expires automatically.** The store can set a number of days in the pickup settings, but no job uses it. Staff cancel manually.
- **Recommended default:** a ready reservation expires 3 days after it became ready, with a reminder, then stock is released automatically and the customer is told. Starting at 3 days is typical; you choose the number.
- **Consequence:** a scheduled job, a notification (D10), clear wording to customers, and tests. Payments already received stay on the ledger (see D1).
- **Question:** hold period in days, and what happens to money already paid when a reservation expires?

### D7. Customer cancellation
- **STATUS: APPROVED (pending or confirmed, reason required) and IMPLEMENTED.** `POST /api/v1/reservations/cancel` (reference + phone, same proof and identical 404 as the lookup); the backend answers `canCancel`. Stock is freed at once; the store sees who cancelled and why; the audit trail keeps only the length of the reason.
- **Why:** customers change their mind.
- **Now:** customers cannot cancel; only the store can. The customer page does not show a cancel button.
- **Recommended default:** a customer may cancel while the reservation is *pending or confirmed* (not after it is ready), giving a reason; it frees the stock at once. After ready they contact the store.
- **Consequence:** one customer endpoint (ownership proven by reference + phone or account), a button, history entry "cancelled by customer", and tests.
- **Question:** until which status may a customer cancel?

### D8. Changing the pickup place for one reservation
- **Now:** a pickup change in settings applies to **new** reservations only; each reservation keeps the address it was made with. There is no way to change it for one reservation.
- **Recommended default:** staff can edit one reservation's pickup note with a reason (audited) and the customer sees it.
- **Question:** is this needed at launch?

## C. Catalog and prices

### D9. Wholesale prices: who sees them?
- **Now:** quantity prices are visible to **everyone**.
- **Recommended default:** keep public (simplest, honest). Alternative: only approved wholesale customers see them, which needs an approval workflow and account types.
- **Question:** public, or approved customers only?

### D10. Selling units and fractional quantities
- **Now:** the unit list is unit, pair, pack, box, set, meter, kilogram, but quantities are **whole numbers**, and there is **no conversion** (one box is not "12 units" anywhere). Selling 2.5 meters or 0.5 kg is not possible.
- **Recommended default:** whole numbers only at launch; if you sell by meter or kilo, decide the smallest step (for example 0.5).
- **Consequence of decimals:** stock, prices and reservation quantities become decimals (migration and many tests). Conversions (box to units) need a per-product factor you provide; I will not invent one.
- **Question:** do you sell by length or weight? What is the smallest step?

### D11. Low stock
- **Now:** a product counts as "low" only if you set a threshold on it. No default threshold exists.
- **Recommended default:** show the threshold field on each product and keep the dashboard alert; do not impose a global number.

### D12. Minimum order for the whole shop
- **Now:** minimum order is per product and currency. There is no shop-wide minimum.
- **Question:** do you want a shop-wide minimum (for example a total amount)? It would have to be per currency.

### D13. Featured and new products on the home page
- **Now:** the home page shows the newest products. There is no "featured" flag for hand-picked items.
- **Recommended default:** add a simple "featured" switch per product if you want to choose what is shown first.

## D. People and security

### D14. Staff roles
- **STATUS: APPROVED (Owner / Manager / Staff, table below) and IMPLEMENTED.** Existing members became OWNER; invitations carry the role; the last active owner is protected; the endpoint x role matrix is tested for every admin endpoint. **Assumptions to confirm** (not in the approved table): all staff may VIEW customer e-mail messages and online payment attempts, only Owner/Manager may resend a message; all staff may make their own recovery codes.
- **Now:** everyone on the team has **all** rights: record payments, change prices, invite and remove colleagues. The team page says so.
- **Recommended default:** two roles: **Owner** (everything, including team and settings) and **Staff** (reservations, payments, inventory, no prices, no team). Only an Owner can invite or remove people.
- **Consequence:** a role on each team member, checks in every protected endpoint, tests per role, and a screen change. About a week.
- **Question:** which people need which rights?
- **Recommended permission model (practical, three roles, fixed in code, no custom roles):**

| Capability | Owner | Manager | Staff (counter) |
|---|---|---|---|
| Reservations: view, accept, reject, ready, collect, cancel, notes | yes | yes | yes |
| Record a cash payment | yes | yes | yes |
| Reverse or adjust a payment, (future) refund | yes | yes | no |
| Inventory: view | yes | yes | yes |
| Inventory: adjust stock (reason mandatory) | yes | yes | no |
| Products, categories, prices, images | yes | yes | no |
| Customers: view | yes | yes | yes (no edit) |
| Reports (money figures) | yes | yes | no |
| Pickup settings and store settings | yes | no | no |
| Team: invite, remove, restore | yes | no | no |
- **Affected components:** *Database:* migration adds `staff_members.role` (`OWNER`/`MANAGER`/`STAFF`, existing members become `OWNER` so nobody loses access), invitations carry the role to grant. *Backend:* roles become authorities in `StaffAuthoritiesConverter`; `SecurityConfig` path rules per capability (deny by default stays); `/me.permissions` (already in the contract, empty today) lists capabilities; last-active-**Owner** protection replaces last-active-member; *Frontend:* `lib/auth/authorization.ts#hasPermission` (exists, unused) drives which nav items and actions are shown (display only; the API decides); team page shows and sets the role. *Tests:* a generated endpoint x role matrix (every `/admin/**` path asserts 200/403 per role), role change takes effect on the next request, MFA applies to every role. *Effort:* about one week.

### D15. Mandatory second step for staff sign-in (MFA)
- **STATUS: APPROVED (required in production, own recovery codes) and IMPLEMENTED.** The production profile defaults `ANA_STAFF_REQUIRE_MFA` to true (start-up logs an error if it is turned off). Recovery codes: 10 per member, shown once, stored as member-bound SHA-256 hashes, single use, every use audited, redeeming is rate limited (5 per 15 min). A redeemed code opens THAT sign-in session for `ANA_STAFF_RECOVERY_SESSION_TTL` (default 4 h) so a member whose phone is lost is not locked out; the operator still has to remove the lost factor (the application deliberately has no service-role key). **Before the first production sign-in:** every member enrols and makes a set of codes (the Team page shows how many each member has).
- **Now:** implemented and **verified on the local test setup**. A setting makes it mandatory (`ANA_STAFF_REQUIRE_MFA`, off by default so nobody is locked out by surprise). With it on, a team member must enter a 6-digit code from an authenticator app after the password.
- **Recommended default:** **turn it on for production**, and make sure at least two people have enrolled before enforcing.
- **Hosted note:** the hosted Supabase project must have TOTP multi-factor enabled in its settings; this is only verifiable once that project exists.
- **Question:** approve "required for all team members in production"?
- **Recovery and lock-out (read before approving):** TOTP has no recovery codes in Supabase. A member who loses the authenticator device cannot reach the administration until an operator removes that user's factor (Supabase dashboard or Admin API with the service-role key, which the application deliberately never holds); the member then enrols a new device. If **every** member is locked out, only that operator path restores access: so enrol at least two people before enforcing, keep the operator procedure written down (`docs/auth.md`), and never remove the last Owner. Brute-force on codes is limited by Supabase's own MFA rate limits (hosted behaviour to be verified on the staging project).

### D16. Sign-in cookie visible to the page's scripts
- **STATUS: OPEN (your decision).** Evidence: the cookie must be readable by the browser client; HttpOnly needs a server-side session proxy (large change, not approved). Done without a decision: `SameSite=Lax`, `Path=/` and `Secure` (HTTPS) are now stated explicitly. Recommendation unchanged: accept for launch.
- **Now:** the browser keeps the sign-in session in a cookie that the page's own scripts can read (this is how the Supabase library works when the page calls the API directly). It is protected by a strict content policy, HTTPS and short-lived tokens, but a script injected into a page could read it.
- **Recommended default:** accept for launch, with the strict policy and MFA above; revisit when you have traffic and a security review.
- **Alternative:** route all API calls through the web server so the cookie can be hidden from scripts (a large change: every call, and a new server layer).
- **Question:** accept the risk for launch, or require the larger change before launch?

### D17. Content policy still allows inline scripts
- **STATUS: OPEN (your decision), now with measurements.** Each public page contains 10-20 inline scripts (86-194 KB of per-request data), so a hash-based policy cannot work, and a nonce policy makes every page render dynamically, which Next.js documents as incompatible with Partial Prerendering (speed and cost). Everything else in the policy is already strict. Recommendation unchanged: accept for launch; use Report-Only (`SECURITY_CSP_REPORT_ONLY`) in staging to watch for violations.
- **Now:** the policy allows inline scripts because Next.js needs them to start a page; removing that requires the server to build every page for every visitor (slower, more costly), which fights the speed goal.
- **Recommended default:** accept for launch (images, frames, forms and external scripts are already locked down).

## E. Words and law

### D18. Legal pages and terms
- **Now:** privacy, returns and terms pages are placeholders; the terms version label must be set and match in both applications before reservations work.
- **I cannot write legal text.** Needed: the text (French, Haitian Creole), reviewed by you or a lawyer, and a version label such as `ana-depot-terms-2026-11-v1`.

### D19. How customers are told about their reservation (notifications): DECIDED and IMPLEMENTED
- **Decision (owner):** customer e-mail is mandatory for launch, sent by this API (SMTP, `JavaMailSender`, Thymeleaf, transactional outbox). Details, event catalog, retry rules and the production checklist: [notifications.md](notifications.md).
- **Built:** ten messages in French and Haitian Creole (received, accepted, rejected, ready, collected, cancelled, expired, payment received, payment failed, checkout expired), a queue with retry/backoff/dead-letter, a staff screen with a controlled resend, start-up diagnostics, and the SMTP variables in the staging template.
- **Still needed from the owner (cannot be built):** (1) choose and create the SMTP provider account; (2) a sender address on a domain you control, with SPF/DKIM/DMARC set up; (3) the legal links (D18); (4) a native Haitian Creole review of `email/messages_ht.properties`; (5) your approval of the wording of each message (French reviewed by you, Creole by a native speaker).
- **Review of the 72-hour rule and state-change suppression (2026-11-19):** a message about a state the reservation has left is now `SUPERSEDED` (deliberate, not a failure, not counted as one) and a message that waited too long is `EXPIRED`. Important messages (decision, ready, cancelled, expired, expiring reminder, payment received) keep a 14-day limit (informational ones 72 h), are never dropped by the per-address throttle (only what a public action can cause is throttled), and an expired important message raises a staff warning and can be resent with a new age. **Please confirm the 14-day / 72-hour limits** (configurable: `ANA_MAIL_IMPORTANT_MAX_AGE`, `ANA_MAIL_MAX_AGE`).
- **Open sub-decisions (recommended defaults in brackets):** guests give an address that nobody verifies, so a stranger could type someone else's address: accept the low residual risk [recommended: accept, the messages carry no phone number or private data and are throttled per address], or require the guest to confirm the address first (extra step, fewer reservations). A message reminding customers before expiry needs D6. A refund message needs D1.

### D20. Store contact details and opening hours
- **Now:** the pickup settings hold address, phone and opening hours. There is **no** separate store-identity page (name for receipts, email, WhatsApp, social links); the contact page says details will be communicated.
- **Question:** which contact details should customers see, and should they all come from one settings page?

### D21. Haitian Creole review
- **STATUS: OPEN - needs a native speaker (cannot be done by the assistant).** `ana-depot-web/docs/creole-review-pack.csv` now lists all 2,117 Creole strings (application and e-mails) beside the French, ready for the reviewer; corrections will be applied when approved.
- All Creole text is written carefully but **no native speaker has reviewed it**. `ana-depot-web/docs/creole-review.md` lists the points to check. Needed before launch: a native reviewer.
