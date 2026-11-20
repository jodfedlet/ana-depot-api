# Customer e-mail notifications

Status: **implemented** (migration V16, `core/notification`, `infra/notification`, admin screen). Decision D19 is resolved: customer e-mail, sent by this API through Spring's `JavaMailSender` over a configurable SMTP server, rendered with Thymeleaf, queued in PostgreSQL. No third-party notification API, no Supabase e-mail, no e-mail call from the browser.

What is **not** verified: delivery through a real hosted SMTP provider, deliverability (SPF/DKIM/DMARC, spam filtering), and rendering in every real mail client. Everything below was verified against an in-process SMTP server (GreenMail), a local Mailpit catcher and PostgreSQL (see "Evidence").

## 1. Architecture

```
business transaction (reservation transition / payment recorded / verified Stripe event)
   └─ NotificationRecorder.record(...)  ── same transaction ──►  notifications row (PENDING)        [core.notification, infra persistence]
                                                                      │  commit
NotificationDispatcher (scheduled, any number of workers)             ▼
   1. claim    FOR UPDATE SKIP LOCKED, status PROCESSING + lease, attempts+1, COMMIT          (no lock is held while mail is sent)
   2. build    NotificationContentAssembler: reservation snapshot, payments, pickup snapshot   (read-only, from committed records)
   3. render   EmailRenderer: Thymeleaf HTML + plain text, fr or ht
   4. send     MailTransport: SmtpMailTransport (JavaMailSender) | CaptureMailTransport (dev: .eml files)
   5. settle   SENT | CAPTURED | RETRY (exponential backoff + jitter) | DEAD
```
* **Email is never sent inside a business transaction**, and a failure to send never affects one (tested: SMTP down while a reservation is collected and paid).
* **Atomicity:** the row commits or rolls back with the operation, so a message cannot be lost nor announce something that did not happen. The row holds *references* (reservation, payment, attempt) and a masked recipient hint, never the body and never the address; the address is read at send time from the reservation's contact snapshot.
* **Layering:** `core.notification` is plain Java (model, ports `NotificationRepository`, `NotificationRecorder`, `NotificationAdminService`, `serviceImpl`). `infra.notification` holds JPA, JavaMailSender, Thymeleaf, the dispatcher and the controller. `core` imports no mail or template library (`ArchitectureTest`).
* **Truth at send time:** content is built when the message is sent, from the current records, and a message about a state the reservation has left is **not sent** (`state_changed`, status DEAD, visible): "ready for pickup" is never mailed after the reservation was collected. A message older than its limit is not sent either (see "Outcomes that are not failures"): for example one that waited while mail was disabled.

## 2. Event catalog
Each event is emitted from exactly one backend operation, inside its transaction. Dedupe key = one message per business fact.

| Notification | Emitted by | Dedupe key | Sent when |
|---|---|---|---|
| `RESERVATION_SUBMITTED` | `ReservationServiceImpl.submit` | type + reservation | always (reference, date, lines with variant/quantity/unit/price/subtotal, **one total per currency**, pickup as snapshotted or "confirmation pending", tracking instructions) |
| `RESERVATION_CONFIRMED` | `accept` | type + reservation | reservation is (still) confirmed or later; shows pickup, payment options now available, outstanding balance **per currency** |
| `RESERVATION_REJECTED` | `reject` | type + reservation | reason = the **code's** customer text only; staff-written notes are never shown |
| `RESERVATION_READY_FOR_PICKUP` | `markReady` | type + reservation | reservation is still ready; pickup address and hours **from the reservation snapshot**, instructions, balances per currency, payment methods that really exist now |
| `RESERVATION_COLLECTED` | `collect` | type + reservation | payment summary from the ledger |
| `RESERVATION_CANCELLED` | `cancel` | type + reservation | "cancelled by the store"; the staff reason is never shown; payments recorded are listed, no refund is promised |
| `RESERVATION_EXPIRED` | `expire` | type + reservation | exists only because the transition exists; nothing expires automatically until D6 is decided |
| `PAYMENT_RECEIVED` | `PaymentServiceImpl.publish("payment.recorded")`: cash recorded by staff **and** `recordProviderPayment` (verified Stripe event) | payment id | the payment is in the ledger and not reversed; amount and currency exactly as recorded; method named truthfully (card online / cash at the store); balance per currency |
| `PAYMENT_FAILED` | `OnlinePaymentServiceImpl.terminal(FAILED)` (verified provider event, first terminal event only) | attempt id | "not confirmed, nothing recorded, reservation kept" |
| `CHECKOUT_EXPIRED` | `terminal(EXPIRED)` (verified provider event, first terminal event only) | attempt id | same wording for an expired session |

**Defined but deliberately not sent:**
* *Checkout initiated*: the customer is on the page that redirected them; an e-mail containing a payment link would train customers to click payment links in e-mails (phishing pattern) and adds nothing. No such e-mail exists.
* *Refund completed*: refunds are not supported or approved (D1); the type does not exist yet. When refunds are built, the e-mail is sent only after the provider confirms the refund and the ledger records it.
* *Welcome / verification / password reset*: **Supabase Auth owns these.** The catalog has no authentication type and no such template (tested: `NotificationCoreTest.theCatalogHasNoAuthenticationMessages`, `EmailRenderingTest.everyTemplateAndMessageKeyExistsInBothLanguages`, and the browser check that creating a Supabase account produces no message from this application's SMTP sender). An application welcome e-mail is not implemented, so nothing can imply an unverified account is verified.

**Payment integrity:** a receipt exists only for a payment recorded in the ledger, which for cards happens only from a signature-verified webhook. Returning from Stripe's success URL, an unsigned or tampered webhook, an amount or currency mismatch, a duplicate delivery, the same payment under another event id, and a failure/expiry arriving after success all produce **no extra or false receipt** (`OnlinePaymentIntegrationTest.noReceiptExistsUntil…`, `aMismatched…QueuesNoReceipt`, `aFailedOrExpiredCheckout…`).

## 2b. Outcomes that are not failures, and important messages (review of 2026-11-19)
* **SUPERSEDED** - the message would no longer be true (for example "ready for pickup" after the reservation was collected): deliberately not sent. It is informational, not counted as a failure, not resendable (it would not be true).
* **EXPIRED** - the message waited longer than its limit and was not sent. Informational messages: `ANA_MAIL_MAX_AGE` (72 h). **Important** messages (`NotificationType.important()`: confirmed, rejected, ready, cancelled, expired, expiring reminder, payment received): `ANA_MAIL_IMPORTANT_MAX_AGE` (14 days, a proposal awaiting your confirmation). An expired important message means the customer may not have been told: it is counted in `attention` (dashboard warning, gauge `anadepot.notifications.attention`) and staff can resend it from the Messages screen; resending restarts the message's age (`revived_at`) and the content is still checked against the reservation's current state before it goes out.
* **Throttle** (`ANA_MAIL_PER_RECIPIENT_PER_HOUR`) now holds back only what a PUBLIC action can cause (reservation submitted, checkout failed/expired). A decision by the store or a verified provider event is never dropped by it.
* New messages: `RESERVATION_EXPIRING` (reminder `ANA_RESERVATION_REMINDER_LEAD` before the pickup deadline, D6) and `RESERVATION_CANCELLED_BY_CUSTOMER` (confirmation of the customer's own cancellation, D7). Twelve message types x fr + ht.

## 3. Template catalog
Ten events x two languages. HTML and plain text are rendered from the same view in `templates/email/`:

| Template (`html/<name>.html`, `text/<name>.txt`) | Type |
|---|---|
| `reservation-submitted` | RESERVATION_SUBMITTED |
| `reservation-confirmed` | RESERVATION_CONFIRMED |
| `reservation-rejected` | RESERVATION_REJECTED |
| `reservation-ready` | RESERVATION_READY_FOR_PICKUP |
| `reservation-collected` | RESERVATION_COLLECTED |
| `reservation-cancelled` | RESERVATION_CANCELLED |
| `reservation-expired` | RESERVATION_EXPIRED |
| `payment-received` | PAYMENT_RECEIVED |
| `payment-failed` | PAYMENT_FAILED |
| `checkout-expired` | CHECKOUT_EXPIRED |

* **Reusable fragments** (`html/fragments.html`): layout (header with logo mark, footer, legal links), brand colours, button, reference box, items, per-currency totals, per-currency balances, payment options, pickup, payments, support contact.
* **Languages:** the structure is shared and the words come from message catalogs `email/messages_fr.properties` (also the fallback) and `email/messages_ht.properties`, UTF-8. The language is the reservation's `locale`. **Haitian Creole is not native-reviewed** (`docs/creole-review.md`).
* **Brand:** warm canvas `#fbf9f6`, deep teal `#0b3732`/`#0f4640` header and headings, terracotta `#b5441b` accent rule and buttons, ink `#1c1b19`; contrast above 4.5:1; the "AD" mark is a table cell (no remote image to be blocked).
* **Email-client safety:** table layout with inline styles, a small `<style>` block only for the phone breakpoint (a client that strips it still shows a readable single column), no JavaScript, no external CSS, no remote images; essential content never depends on any of them. A plain-text part is always sent (multipart/alternative).
* **Escaping:** only `th:text` is used, never `th:utext`: a customer name or product name containing markup is shown as text (tested with `"><img onerror>` and `<script>`).
* **Nothing invented:** support contact comes from the pickup settings (`email`, `phone`) and is omitted when not configured; legal links appear only when `ANA_MAIL_TERMS_URL` / `ANA_MAIL_PRIVACY_URL` are set; the tracking button appears only when `ANA_WEB_BASE_URL` is set (otherwise the e-mail explains in words); pickup details come from the snapshot or the message says confirmation is pending.
* **What a customer e-mail can never contain:** internal notes, staff names or ids, the customer's phone number, ledger or provider references, card details, passwords or tokens (`EmailView` has no field for them).

## 4. SMTP and environment variables
All values come from the environment (or a secret manager); nothing real is committed; `MailProperties.toString()` never prints the password. The complete list is in `docs/staging/api.staging.env.template`.

| Variable | Default | Meaning |
|---|---|---|
| `ANA_MAIL_MODE` | `log` (dev/test), `smtp` (prod profile) | `smtp` sends; `log` writes `.eml` files and **sends nothing** (rows end CAPTURED); `disabled` queues and does **not** send |
| `ANA_MAIL_SMTP_HOST`, `ANA_MAIL_SMTP_PORT` | - / `587` | server |
| `ANA_MAIL_SMTP_USERNAME`, `ANA_MAIL_SMTP_PASSWORD` | - | credentials (required while `ANA_MAIL_SMTP_AUTH=true`) |
| `ANA_MAIL_SMTP_AUTH` | `true` | |
| `ANA_MAIL_SMTP_STARTTLS` | `true` | upgrade to TLS and **require** it (no fall-back to plain text) |
| `ANA_MAIL_SMTP_SSL` | `false` | implicit TLS (usually port 465); exclusive with STARTTLS; the certificate host name is verified in both |
| `ANA_MAIL_SMTP_CONNECTION_TIMEOUT`, `..._READ_TIMEOUT`, `..._WRITE_TIMEOUT` | `5s`, `10s`, `10s` | every wait on the network is bounded |
| `ANA_MAIL_FROM_ADDRESS`, `ANA_MAIL_FROM_NAME` | -, `Ana Dépôt` | sender (the address must be one your provider lets you send from) |
| `ANA_MAIL_REPLY_TO` | - | optional |
| `ANA_WEB_BASE_URL` | - | storefront origin for the tracking link |
| `ANA_MAIL_TERMS_URL`, `ANA_MAIL_PRIVACY_URL` | - | optional legal links |
| `ANA_MAIL_PER_RECIPIENT_PER_HOUR` | `10` | one address can only be written to so often |
| `ANA_MAIL_MAX_ATTEMPTS` | `8` | then DEAD |
| `ANA_MAIL_INITIAL_BACKOFF`, `ANA_MAIL_POLL_INTERVAL` | `PT1M`, `PT5S` | retry delay (doubles, jittered, capped at 6 h) and polling |
| `ANA_MAIL_MAX_AGE` | `72h` | an informational message older than this is not sent (status EXPIRED) |
| `ANA_MAIL_IMPORTANT_MAX_AGE` | `14d` | the same for an important message; an expired one raises a staff warning |
| `ANA_MAIL_DISPATCHER_ENABLED` | `true` | switch the sender off on an instance |
| `ANA_MAIL_CAPTURE_DIR` | `target/mail-capture` | where `log` mode writes `.eml` files |

**Start-up diagnostic:** in `smtp` mode a missing or inconsistent setting stops the application with a message that names the variables (never their values), for example `ANA_MAIL_SMTP_HOST is not set`, and tells you the two explicit alternatives. The production profile defaults to `smtp`, so a production deployment without mail settings does not start; `ANA_MAIL_MODE=disabled` is an explicit, logged opt-out. The application always starts in local development and tests without SMTP (mode `log`).

## 5. Retry, dead-letter, duplicates
* **Attempts:** at most `ANA_MAIL_MAX_ATTEMPTS` (8). Delay after attempt *n* = random in [cap/2, cap] with cap = min(6 h, 1 min x 2^(n-1)) (about 1, 2, 4, 8, 16, 32, 64 minutes), same `RetryPolicy` as the outbox.
* **Classified failures** (stored as a code only, never provider text, an address or a secret): `smtp_unavailable`, `smtp_timeout`, `smtp_auth_failed`, `smtp_error` are retried (a wrong password is a configuration problem: the queue resumes once it is fixed, within the budget); `recipient_rejected`, `recipient_invalid`, `render_failed`, `mail_build_failed`, `state_changed`, `stale`, `data_missing`, `payment_not_found` are permanent (DEAD at once).
* **DEAD** = gave up. Never retried by itself. Visible in the admin screen, the dashboard and the gauge `anadepot.notifications.dead`; staff can resend it (below).
* **Concurrent workers:** claims use `FOR UPDATE SKIP LOCKED`, so workers never share a row; a worker that dies leaves an expired lease and another sends the message; a worker that lost its lease cannot overwrite the new owner's result (attempt-number check). Tested with 8 simultaneous workers over 24 messages: each sent exactly once.
* **Duplicates:** unique `dedupe_key` (one row per business fact, whatever the number of duplicate events or webhooks), never re-sending a SENT row, and a stable `X-Ana-Notification-Id` header. **SMTP cannot give exactly-once:** if the connection drops after the server accepted a message but before we record SENT, the retry can deliver ONE duplicate. That is the accepted trade-off (a duplicate is preferred to a lost message) and it is documented, not hidden.
* **Throttle:** at most `ANA_MAIL_PER_RECIPIENT_PER_HOUR` queued messages per address per hour (`SKIPPED_THROTTLED`): the reservation form accepts any address, so this stops the shop from being used to flood a stranger.

## 6. Staff visibility and retry
`GET /api/v1/admin/notifications?status&type&reservationId&page&size`, `GET .../summary` (counts per status, `mode` SMTP/LOG/DISABLED, oldest waiting), `POST .../{id}/retry` (DEAD only, `409 notification_not_retryable` otherwise, audited `NOTIFICATION_RETRY_REQUESTED` without address or body, safe under concurrent clicks: exactly one succeeds). Staff only. Each row: type, reservation reference, **masked** recipient (`m***@example.com`), creation and sent time, status, attempts, failure code, next attempt. Nothing about the mail server (host, credentials, provider text) is ever returned.
Web: **Administration > Messages clients** (summary tiles, filters, plain-language failure text, confirmed "Resend"), a "Messages envoyés au client" block on each reservation, and a dashboard warning when messages failed or sending is disabled.

## 7. Security and privacy
* Transactional messages only; no marketing, no subscription mixing, nothing that is not needed to fulfil a reservation or a payment.
* The address comes from the reservation's contact snapshot (validated at reservation time and again before sending). It is **unverified for guests**: a stranger could type someone else's address. Mitigations in place: no phone number or private data in the message, a footer saying to ignore a message about a reservation you did not make, per-address throttling and the reservation rate limit. Residual risk: one unsolicited, low-detail message per reservation. Verified addresses for guests would need a confirmation step (not built; owner decision if wanted).
* No credentials, tokens or card data are ever mailed; no secret is logged; failures are codes.
* Logs and metrics carry ids and codes, never addresses or bodies (`OutgoingMail`, `NotificationRequest`, `Notification` print nothing sensitive: tested).

## 8. Local development
* Default mode `log`: run the API and open `target/mail-capture/*.eml` in any mail client. Rows end `CAPTURED` (never `SENT`); the admin screen shows a development banner.
* Real SMTP, locally: run a catcher (for example Mailpit: `docker run -p 127.0.0.1:54325:1025 -p 127.0.0.1:54326:8025 axllent/mailpit`) and start the API with `ANA_MAIL_MODE=smtp ANA_MAIL_SMTP_HOST=127.0.0.1 ANA_MAIL_SMTP_PORT=54325 ANA_MAIL_SMTP_AUTH=false ANA_MAIL_SMTP_STARTTLS=false ANA_MAIL_FROM_ADDRESS=no-reply@ana-depot.test`; messages appear at `http://127.0.0.1:54326`.
* Look at every template without a server: `mvn test -Dtest=EmailRenderingTest` writes all 40 renderings to `target/email-samples/` (open the `.html` files in a browser, resize to a phone width).
* Tests: GreenMail (real SMTP in-process) for transport behaviour; a scripted transport for the workflow; PostgreSQL via Testcontainers.

## 9. Operations: when something fails
1. Dashboard warning or the Messages screen shows failures. Read the plain-language reason.
2. `smtp_auth_failed` / `smtp_unavailable` / `smtp_timeout` on many messages = provider or configuration: fix credentials, host or firewall; queued messages retry by themselves within the attempt budget; DEAD ones are resent from the screen after the fix.
3. `recipient_rejected` / `recipient_invalid`: the customer's address is wrong; contact them by phone; resending will not help.
4. `SUPERSEDED` (`state_changed`): informational: the message would no longer be true. Do not resend. `EXPIRED` (`stale`) on an important message: the customer may not know: resend it (the screen checks it is still true) or phone them.
5. `SKIPPED_NO_RECIPIENT`: the guest gave no address: use the phone number.
6. Alerts: `anadepot.notifications.dead > 0`, and `anadepot.notifications.oldest.waiting.age` growing (the sender is stuck or mail is disabled).

## 10. Production SMTP setup checklist (owner and operator)
- [ ] Choose an SMTP provider (not chosen by this project; any provider with SMTP submission works). Create the account yourself.
- [ ] Own a sender domain and configure **SPF, DKIM and DMARC** for it with the provider; verify the From address/domain.
- [ ] Set `ANA_MAIL_MODE=smtp`, host, port (587 + STARTTLS, or 465 + SSL), username and password **in the secret store**, `ANA_MAIL_FROM_ADDRESS`, optional `ANA_MAIL_REPLY_TO`, `ANA_WEB_BASE_URL`, legal links.
- [ ] Start the API: it refuses to start if anything required is missing.
- [ ] Send a test reservation to an address you control; read the message in French **and** Haitian Creole in at least Gmail (web and phone) and one other client; check spam placement and the plain-text part.
- [ ] Have a native Haitian Creole speaker review `email/messages_ht.properties`.
- [ ] Confirm the store's contact e-mail and phone are set in the pickup settings (they appear in the message).
- [ ] Review the legal wording linked from the footer (D18).
- [ ] Add the two alerts of section 9; write down who watches them.
- [ ] Decide D6 (expiry) before relying on `RESERVATION_EXPIRED`, and D1 (refunds) before promising anything about money returned.

## Evidence
`NotificationCoreTest` (6), `EmailRenderingTest` (21, both languages, escaping, currency separation, no-invention rules), `SmtpMailTransportTest` (7, real SMTP: delivery and MIME, wrong password, unavailable server, silent server timeout, classification, start-up diagnostic), `NotificationFlowIntegrationTest` (22, real PostgreSQL), `AdminNotificationApiIntegrationTest` (6), webhook integrity in `OnlinePaymentIntegrationTest`, and the browser suite `npm run e2e:notifications` (real SMTP to a local Mailpit, container stopped and started for the outage). Label for all of it: **local**; nothing was sent to a real provider.
