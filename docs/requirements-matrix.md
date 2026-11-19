# Requirements traceability matrix

Both repositories (`ana-depot-api`, `ana-depot-web`). Status vocabulary: **PASS** fully implemented and verified; **PARTIAL** implemented incompletely; **FAIL** missing or wrong; **BLOCKED** needs a business decision (see [decisions.md](decisions.md), `D#`) or an external dependency; **NOT REQUIRED** outside the approved scope.

**Evidence levels** (never mixed): **UNIT/INT** = automated tests with a real PostgreSQL (Testcontainers) and the real security chain; **LOCAL-E2E** = real browser against the real API, a LOCAL Supabase (GoTrue, Mailpit), MinIO standing in for R2; **FAKE** = local fake Stripe or a mocked gateway; **MOCK** = mocked provider. **Nothing in this file is hosted-provider evidence**: hosted Supabase, real R2, real Stripe, real e-mail delivery and TLS/edge behaviour are BLOCKED until the owner supplies those resources (`docs/staging/`).

Requirements were recovered from the repositories' documentation (`docs/domain.md`, `integration-map.md`, `api.md`, ADRs, both `CLAUDE.md`), the commit history, the project memory (stack decisions, UX standard) and the briefs given in this project. Where the sources disagree the code and tests decided; the disagreements found are listed in "Inconsistencies found".

Column "Where": FE = `ana-depot-web/src/...`, BE = `ana-depot-api/src/main/java/com/anadepot/...`, DB = migration. Tests: API classes in `src/test/java/com/anadepot/`, web unit in `src/**/*.test.ts`, browser suites `npm run e2e:*`.

## 1. Catalog
| ID | Requirement | Importance | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| CAT-1 | Dynamic categories, no code change for new ones | High | PASS | FE `features/admin/categories`; BE `core/catalog`, `infra/catalog`; DB V1 | `AdminCategoryApiIntegrationTest`, `CatalogPersistenceIntegrationTest`, e2e:admin | — |
| CAT-2 | Attributes with types (CHOICE/TEXT/NUMBER), required, filterable | High | PASS | same; `lib/admin/category-form.ts` | `CatalogConstraintsIntegrationTest`, `category-form.test.ts`, e2e:admin | Only CHOICE can be filterable/axis (by design, ADR/domain.md) |
| CAT-3 | Variant axes and product variants | High | PASS | BE `Product`, `ProductVariant`; FE `features/admin/products` | `AdminProductApiIntegrationTest`, `ProductPricingTest`, e2e:admin | Axes are the category's CHOICE attributes; ad-hoc per-product options are not modelled (documented design) |
| CAT-4 | Lifecycle draft / active / inactive; non-active never public | High | PASS | BE `Product.requirePublishable`; FE proxy 404 | `PublicCatalogApiIntegrationTest`, `catalog-exists.test.ts`, e2e:storefront (real 404) | — |
| CAT-5 | Product images, ordering | High | PASS (local) / BLOCKED (real R2) | BE `infra/storage`; FE `lib/api/admin/upload.ts` | `AdminProductImageApiIntegrationTest`, `UploadLifecycleIntegrationTest`, `R2PresignTest`, `R2StorageMinioIntegrationTest`; e2e:admin | Real R2 never exercised (`R2RealIntegrationTest` opt-in; needs bucket) |
| CAT-6 | Descriptions and translations (fr/ht) | High | PASS | `LocalizedText`; FE `LText` | `CatalogPersistenceIntegrationTest`, `map-catalog.test.ts` | Creole text unreviewed (LOC-3) |
| CAT-7 | Search, sort, filter, pagination (PostgreSQL FTS only) | High | PASS | BE `ProductSearchNative` (ADR 0015); DB V7, V8 | `CatalogFullTextSearchIntegrationTest`, `CatalogSearchPlanIntegrationTest`, `CatalogSearchPerformanceTest`, `SearchQueryTest`, `catalog-query.test.ts` | — |
| CAT-8 | Product detail pages (server-rendered) | High | PASS | FE `app/[locale]/(store)/products/[slug]`, `lib/api/server-catalog.ts` | e2e:storefront, `seo.test.ts` | — |
| CAT-9 | Related products | Low | PASS | FE `useProductPage` (same category, API search) | e2e:storefront | — |
| CAT-10 | Featured / "new" curation on the home page | Low | PARTIAL | FE home uses newest | e2e:storefront | No featured flag exists: **D13** |

## 2. Pricing, units, inventory
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| PRC-1 | HTG and USD; independent price per currency on ONE product | Critical | PASS | `ProductPricing`; DB V1 (`product_prices`) | `ProductPricingTest`, `CatalogConstraintsIntegrationTest`, `MoneyTest`, `pricing.test.ts` | — |
| PRC-2 | Retail, sale, optional wholesale tiers per currency | Critical | PASS | same | `ProductPricingTest`, `GuestReservationApiIntegrationTest.wholesaleTier…` | Visibility: **D9** (public today) |
| PRC-3 | No automatic conversion, never mix currencies | Critical | PASS | `Money`, `CurrencyTotals` | `MoneyTest`, `ProductPricingTest`, `OnlinePaymentIntegrationTest.aCurrencyTheProviderCannot…`, `money.test.ts`, e2e (mixed carts) | — |
| PRC-4 | Reservation price snapshots; later edits never alter them | Critical | PASS | `ReservationLine` | `ReservationTest`, `ReservationConstraintsIntegrationTest`, `GuestReservationApiIntegrationTest` | — |
| PRC-5 | Decimal precision (no float), currency scale | Critical | PASS | `Money` | `MoneyTest`, `decimal.test.ts` | — |
| UNT-1 | Flexible selling units (unit, pair, pack, box, set, meter, kilogram) | Medium | PASS | `SellingUnit` text column; FE unit ids | `product-form.test.ts`, e2e:admin | — |
| UNT-2 | Fractional quantities and pack/box conversion | Medium | BLOCKED | quantities are integers; no conversion | — | **D10** (no factor is invented) |
| INV-1 | on-hand / reserved / available; shared by HTG and USD | Critical | PASS | `InventoryItem`; DB V2 | `InventoryItemTest`, `InventoryConstraintsIntegrationTest` | — |
| INV-2 | No overselling under concurrency | Critical | PASS | row locks (ADR 0009) | `ConcurrencyIntegrationTest`, `GuestReservationApiIntegrationTest.concurrentHttp…` | — |
| INV-3 | Movements, adjustments (reason, idempotent), auditable history | High | PASS | `StockMovement` (append-only trigger) | `AdminInventoryApiIntegrationTest`, `IdempotencyIntegrationTest` | — |
| INV-4 | Stock released on reject / cancel | Critical | PASS | `ReservationServiceImpl` | `ReservationFlowIntegrationTest`, `AdminReservationApiIntegrationTest` | — |
| INV-5 | Low-stock policy | Low | BLOCKED | threshold per item only | `AdminInventoryApiIntegrationTest` | **D11** |

## 3. Customer experience and reservations
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| CUS-1 | Guest shopping and reservation, no forced account | Critical | PASS | `POST /reservations` public | `GuestReservationApiIntegrationTest`, e2e:storefront | — |
| CUS-2 | Registration, login, recovery, e-mail verification (Supabase Auth block, no custom forms) | Critical | PASS (local) / BLOCKED (hosted SMTP) | FE `components/auth-block`, `docs/authentication.md` | `auth.test.ts`, e2e:real, e2e:stub, e2e:unavailable | Hosted deliverability/SMTP: external |
| CUS-3 | Reservation tracking (reference + phone, enumeration-resistant) | Critical | PASS | `POST /reservations/lookup` | `ReservationLookupApiIntegrationTest` (8), `ReservationRateLimitIntegrationTest`, e2e:storefront | — |
| CUS-4 | Account profile and own reservations; guests never merged | High | PASS | `/me/**`; DB V9 | `AccountApiIntegrationTest` (8), `CustomerAccountsPersistenceIntegrationTest` | — |
| CUS-5 | Customer notes separated from internal notes | High | PASS | `CustomerReservationView` (no field for internal data) | `ReservationLookupApiIntegrationTest`, `map-reservation.test.ts` | — |
| CUS-6 | Clear confirmation and status; responsive mobile-first | High | PASS | FE `features/reservations` | e2e:storefront (390/1280), e2e:a11y | — |
| CUS-7 | Customer cancellation | Medium | BLOCKED | no endpoint; `canCancel:false` | `ReservationLookupApiIntegrationTest.thereIsNoCustomerCancelEndpoint` | **D7** |
| RES-1 | Lifecycle pending → confirmed → ready → collected; rejected, cancelled; valid transitions only | Critical | PASS | `Reservation` state machine | `ReservationTest`, `ReservationFlowIntegrationTest`, `AdminReservationApiIntegrationTest`, e2e:admin | — |
| RES-2 | Accepting ≠ ready ≠ paid | Critical | PASS | separate transitions; payment status derived | same | — |
| RES-3 | Rejection / cancellation reasons captured | High | PASS | status history | `AdminReservationApiIntegrationTest` | — |
| RES-4 | Pickup snapshot per reservation; global change never rewrites it | High | PASS | `pickup_snapshot`; V6 | `PickupSettingsApiIntegrationTest`, e2e:admin | Per-reservation override: **D8** |
| RES-5 | Expiry only when configured (no automatic expiry) | High | BLOCKED | `expire()` exists, no endpoint/job; hold days stored unused | `ReservationTest` | **D6** (correctly nothing expires) |
| RES-6 | Collection requires every currency paid; stock handed over once | Critical | PASS | `Reservation.collect` | `ReservationTest`, `ReservationFlowIntegrationTest` | — |
| PCK-1 | Configurable pickup address, hours, instructions; no delivery | High | PASS | `PickupSettings`; V6 | `PickupSettingsApiIntegrationTest` (8), e2e:admin | Delivery: NOT REQUIRED (not approved) |

## 4. Payments and finance
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| PAY-1 | Per-currency balances; a payment settles only its own currency | Critical | PASS | `Reservation.balances`, `PaymentService` | `PaymentLedgerIntegrationTest`, `AdminPaymentApiIntegrationTest`, `OnlinePaymentIntegrationTest.aVerifiedPaymentIsPostedOnce…` | — |
| PAY-2 | Append-only ledger, reversals/adjustments, reconciliation | Critical | PASS | V4 triggers; `PaymentService.reconcile` | `PaymentLedgerIntegrationTest`, `OnlinePaymentIntegrationTest.theCustomerSees…Reconciliation` | — |
| PAY-3 | Idempotent payment writes | Critical | PASS | `IdempotencyService` | `IdempotencyIntegrationTest`, `AdminPaymentApiIntegrationTest` | — |
| PAY-4 | Stripe Checkout: backend-calculated amount, ownership/status/currency checks | Critical | PASS (FAKE) | `OnlinePaymentServiceImpl`, `StripeCheckoutGateway` | `OnlinePaymentIntegrationTest` (23), `StripeGatewayConfigTest`, e2e:stripe-real, e2e:stripe | Real Stripe BLOCKED (**D5**: merchant country/account) |
| PAY-5 | Webhook: signature, duplicates, out-of-order, delayed, mismatch, expiry, failure | Critical | PASS (FAKE) | `StripeWebhookController` | `OnlinePaymentIntegrationTest` (signature, duplicate, concurrent duplicates, out-of-order, async, mismatch, expired) | — |
| PAY-6 | Frontend success message is never proof; no manual "paid" for Stripe | Critical | PASS | status endpoint; staff refusals | `OnlinePaymentIntegrationTest.staffCannotRecordOrReverse…`, e2e:stripe(-real) | — |
| PAY-7 | Customer sees payment history and balances | High | PASS | `CustomerViews`, `PaymentHistory` | `OnlinePaymentIntegrationTest.theCustomerSees…`, `map-reservation.test.ts` | Added in this program |
| PAY-8 | Admin: history, details, method (Stripe/cash), failed attempts, reconciliation, reports by method | High | PASS | `AdminPayment*Controller`, `features/admin/payments`, `reports` | `AdminPaymentApiIntegrationTest`, `AdminReportApiIntegrationTest`, e2e:admin, e2e:stripe-real | Method split added in this program |
| PAY-9 | Partial payments, overpayments, prepayment timing, refunds | High | BLOCKED | current behaviour documented in `payments-stripe.md` | `AdminPaymentApiIntegrationTest` (cash partial/over) | **D1–D4** |
| PAY-11 | Payment wording is truthful and follows what the backend says is available (card online + pay at pickup; never "cash only", never a card advertised when off) | High | PASS | BE `OnlinePaymentController.options`, `CheckoutGateway.availability`; FE `lib/payments/capabilities.ts`, `PaymentCapabilitiesProvider`, ~15 screens | `OnlinePaymentIntegrationTest.theBackendStatesWhichPaymentOptionsExistRightNow` + `whenPickupIsClosed…`, `capabilities.test.ts`, `i18n.test.ts` (payment messaging), e2e:payment-messaging (fr+ht, every state), e2e:stripe-real | Creole unreviewed (**D21**). Receipts/notifications do not exist (**D19**) |
| PAY-10 | Stripe merchant, HTG presentment/settlement, fees | Critical | BLOCKED | Stripe disabled by default | — | **D5**, external |

## 5. Administration
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| ADM-1 | Dashboard, products, categories, inventory, reservations, payments, customers, reports | Critical | PASS | `features/admin/*` ↔ `/api/v1/admin/**` | e2e:admin; all `Admin*ApiIntegrationTest` | — |
| ADM-2 | No demo data, fake buttons or false success | Critical | PASS | demo layer removed (f40104c) | e2e:admin ("no demo data in localStorage") | — |
| ADM-3 | Backend authorization on every protected operation | Critical | PASS | `SecurityConfig` deny-by-default | `SecurityIntegrationTest`, every Admin test's 401/403 cases | — |
| ADM-4 | Store settings / pickup configuration | High | PARTIAL | pickup only | `PickupSettingsApiIntegrationTest` | No store-identity/contact settings: **D20** |
| ADM-5 | Image management (upload, reorder, delete) | High | PASS (local) | see CAT-5 | as CAT-5 | Real R2 BLOCKED |
| ADM-6 | Customer management (guests editable, registered read-only) | Medium | PASS | `AdminCustomerController` | `AdminCustomerApiIntegrationTest` | — |
| ADM-7 | Team management without a database administrator | High | PASS | `core/staff`, `AdminStaffController`, `features/admin/team.tsx`; V15 | `StaffServiceImplTest` (7), `StaffManagementApiIntegrationTest` (7), e2e:team | Added in this program. Roles: **D14** |
| ADM-8 | Confirm destructive actions; clear errors; empty/loading states; search + pagination; responsive | High | PASS | shared `Drawer`, `AdminErrorState` | e2e:admin, e2e:a11y, e2e:team (confirmation, 390 px) | — |

## 5b. Customer e-mail notifications (mandatory for launch)
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| NOT-1 | Reservation e-mails: submitted, confirmed, rejected, ready, collected, cancelled, expired (expiry only where the transition exists) | Critical | PASS (local) | BE `core/notification`, `ReservationServiceImpl.record`, templates `templates/email/html/*`; DB V16 | `NotificationFlowIntegrationTest` (every event, recipient, content), `EmailRenderingTest`, e2e:notifications (real SMTP to a catcher) | Expiry unused until **D6**; real provider BLOCKED |
| NOT-2 | Payment e-mails only from authoritative events: receipt (cash and verified Stripe), failed, checkout expired; never from the success URL; no receipt for unconfirmed money | Critical | PASS (FAKE) | `PaymentServiceImpl.publish`, `OnlinePaymentServiceImpl.terminal` | `OnlinePaymentIntegrationTest.noReceiptExistsUntil…`, `aMismatched…QueuesNoReceipt`, `aFailedOrExpiredCheckout…` (duplicates, out of order, unsigned), `NotificationFlowIntegrationTest.aVerifiedCardPayment…` | Refund e-mail: refunds not approved (**D1**), not built. "Checkout initiated" e-mail deliberately not sent (rationale in notifications.md) |
| NOT-3 | HTG and USD never combined; reservation and pickup snapshots; no internal notes or staff text | Critical | PASS | `EmailView` (no field for internal data), `NotificationContentAssembler` | `EmailRenderingTest.htgAndUsdAreNeverCombined`, `NotificationFlowIntegrationTest` (snapshot, notes, rejection/cancellation text) | — |
| NOT-4 | Transactional outbox: same-transaction recording, unique event id, status, attempts, next retry, last error code, sent time, safe retry, dedupe, concurrent workers, bounded retries with backoff and jitter, dead-letter | Critical | PASS (local) | `notifications` table, `NotificationDispatcher`, `NotificationRepositoryAdapter` | `NotificationFlowIntegrationTest` (retry/backoff, DEAD, lease, 8 concurrent workers, stale, state changed), `NotificationCoreTest` | SMTP cannot be exactly-once: one duplicate is possible after an ambiguous failure (documented) |
| NOT-5 | JavaMailSender over configurable SMTP; timeouts; TLS; secrets never printed; start-up diagnostic; app runs without SMTP in dev/test | Critical | PASS (local) | `MailConfig`, `SmtpMailTransport`, `MailProperties` | `SmtpMailTransportTest` (real SMTP: delivery, auth failure, unavailable, timeout, diagnostic) | Hosted provider and deliverability BLOCKED (external) |
| NOT-6 | Thymeleaf templates, French + Haitian Creole, responsive, plain-text alternative, escaping, brand, no invented contact/legal data | High | PASS | `templates/email/**`, `email/messages_{fr,ht}.properties` | `EmailRenderingTest` (40 renderings, escaping, no-invention), screenshots at 390/700 px | Haitian Creole unreviewed (**D21**); real mail-client rendering not verified |
| NOT-7 | Staff visibility and audited retry; masked recipients | High | PASS | `AdminNotificationController`, `features/admin/notifications.tsx` | `AdminNotificationApiIntegrationTest` (6, incl. 4 concurrent retries), e2e:notifications (outage, DEAD, resend) | — |
| NOT-8 | No duplicate of Supabase authentication e-mails; no welcome e-mail that implies verification | High | PASS | `NotificationType` has no auth type | `NotificationCoreTest.theCatalogHasNoAuthenticationMessages`, `EmailRenderingTest…BothLanguages`, e2e:notifications section E | — |
| NOT-9 | Privacy: transactional only, recipient validation, throttling, no sensitive data | High | PARTIAL | `NotificationRecorderImpl`, `EmailAddress` | `NotificationCoreTest`, `NotificationFlowIntegrationTest.oneAddressCannotBeFlooded…` | A guest's address is unverified: residual low-detail risk, owner decision in **D19** |

## 6. Authentication and security
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| SEC-1 | Supabase Auth; no Clerk/Auth0/Keycloak; embedded UI block; no custom login forms | Critical | PASS | `components/auth-block` | `auth-block.test.ts`, e2e:stub/real | — |
| SEC-2 | Staff authorization from our table only (never token claims) | Critical | PASS | `StaffDirectory` | `SecurityIntegrationTest` (metadata grants nothing) | — |
| SEC-3 | Staff second factor | High | PASS (LOCAL) / BLOCKED (hosted) | `StaffAuthoritiesConverter`, `StaffMfa`; `ANA_STAFF_REQUIRE_MFA` | `StaffMfaApiIntegrationTest` (3), `auth.test.ts`, e2e:team (real GoTrue TOTP, aal2) | Added in this program. Default OFF until approved (**D15**); hosted Supabase must enable TOTP |
| SEC-4 | Customer data isolation, reservation ownership | Critical | PASS | `/me/**`, lookup | `AccountApiIntegrationTest.anotherCustomersReservation…`, e2e:storefront (cross-customer) | — |
| SEC-5 | Session lifecycle: refresh, logout, recovery, cookie | High | PASS (local) | `proxy.ts`, `lib/auth` | e2e:real, `cookie-options.test.ts` | Hosted not verified |
| SEC-6 | CSRF | High | PASS | bearer-token API, exact CORS, credentials off; forms POST | `CorsIntegrationTest`, `form-method.test.ts` | — |
| SEC-7 | XSS: CSP and escaping | High | PARTIAL | `lib/security/csp.ts` | `csp.test.ts`, `SecurityHeadersIntegrationTest` | `script-src 'unsafe-inline'` remains (Next inline bootstrap needs per-request nonces = dynamic rendering): **D17** |
| SEC-8 | Auth cookie not readable by scripts | High | PARTIAL / BLOCKED | `@supabase/ssr` browser client reads it | `cookie-options.test.ts` (Secure, SameSite) | Needs a BFF layer: **D16** |
| SEC-9 | Rate limiting, input validation, upload authorization, secrets, audit | High | PASS | `RateLimitFilter`, `UploadService`, `scripts/scan-bundle-secrets.mjs` | `RateLimitIntegrationTest`, `AdminProductImageApiIntegrationTest`, `PersonalDataToStringTest` | — |
| SEC-10 | Staff roles / permissions | High | BLOCKED | all-or-nothing | — | **D14** |

## 7. Localization, SEO, PWA
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| LOC-1 | French + Haitian Creole everywhere, same keys, same placeholders, no hardcoded strings | High | PASS | `src/i18n` | `i18n.test.ts` (key parity, placeholders, no untranslated copies), literal-string scan (0), e2e:a11y (fr+ht) | — |
| LOC-2 | Locale fallback and routing | Medium | PASS | `proxy.ts` | e2e:storefront | — |
| LOC-3 | Native Haitian Creole review | High | BLOCKED | `docs/creole-review.md` | — | **D21** (external reviewer) |
| SEO-1 | Server-rendered catalog, metadata, canonical, hreflang, sitemap, robots, OG, truthful JSON-LD, real 404, private pages noindex | High | PASS | `src/lib/seo`, `docs/seo.md` | `seo.test.ts`, `catalog-exists.test.ts`, e2e:storefront, Lighthouse notes | Hosted crawl not verified |
| PWA-1 | Manifest, icons, service worker, offline fallback, update flow, private cache isolation, no false offline success, cart as estimate | High | PASS (local) | `src/pwa`, `src/features/pwa` | `policy.test.ts`, e2e:pwa | HTTPS/Safari/Firefox/physical devices not verified |
| A11Y-1 | Accessibility (WCAG 2.x A/AA automated), keyboard, labels | High | PASS (automated) | all screens | e2e:a11y (axe, 390/1280, fr+ht) | Manual screen-reader review remains |

## 8. Architecture, performance, operations
| ID | Requirement | Imp. | Status | Where | Evidence | Gap / action |
|---|---|---|---|---|---|---|
| ARC-1 | Modular monolith, two top-level packages, core free of Spring/JPA, service/serviceImpl, JPA only (no JDBC) | High | PASS | — | `ArchitectureTest` (6) | — |
| ARC-2 | Flyway immutable migrations, RLS on every table, Data API locked down | Critical | PASS (local) | V1–V16 | `FlywayMigrationIntegrationTest`, `MigrationRlsConventionTest`, `DataApiLockdownIntegrationTest` | Hosted Supabase not verified |
| ARC-3 | Outbox, idempotency, dedup, bounded retries, observability | High | PASS | ADRs 0002–0010 | `OutboxIntegrationTest`, `DeduplicationIntegrationTest`, `RetryPolicyTest`, `ObservabilityIntegrationTest` | — |
| PER-1 | No N+1 on list endpoints | High | PASS | — | `QueryCountIntegrationTest` (statement counts at two data sizes) | Added in this program |
| PER-2 | Request latency <= 1 s | High | PASS (local, sequential) / hosted NOT measured | `CatalogSearchPerformanceTest` (opt-in), `ana-depot-web/scripts/measure-latency.mjs`, `docs/performance.md` | MEASURED over HTTP on the seeded local stack (20 products, 12-CPU machine, 30 samples per route): **34 routes (17 API reads, 11 server-rendered pages, 6 writes: reservation submit, tracking lookup, accept, ready, cash payment, collect), 0 over the 1 s target, slowest p95 64 ms**. Search over **20,000 products** (use-case level): worst p95 107.6 ms | **Not measured:** concurrent load, large reservation/payment tables, hosted + TLS + mobile-network latency, cold start |
| OPS-1 | Health, readiness, metrics on a private port, timeouts, graceful shutdown, container image | High | PASS (local) | `docs/operations.md`, `Dockerfile` | `ManagementPortIntegrationTest`, `StorageHealthIndicatorTest`; image built and run | Hosted/restore drills: staging |

## Inconsistencies found
1. `ana-depot-web/CLAUDE.md` still said "NO online payment" and "each product has `pricing.currency`": both obsolete (Stripe checkout exists; pricing is per currency). Corrected in this program.
2. The Phase-2 integration notes said staff "management endpoints exist" while no staff endpoint existed: the team feature now exists; `docs/auth.md` and `docs/integration-map.md` are updated.
3. `docs/auth.md` told operators to insert staff rows by SQL: replaced by the invitation flow.
4. The customer reservation page always labelled the payment method "cash at pickup" even after a card payment: replaced by the real payment history.

## What is not covered by this matrix
Hosted Supabase (migrations, RLS on the hosted Data API, TOTP setting, SMTP), real Cloudflare R2, real Stripe (any country), TLS/HSTS/edge behaviour, monitoring/alerts, backup restore timing, Safari/Firefox/physical devices. See `docs/staging/`.

## Test results (this program; exact counts)
Run on the final code: API `534191e`, web `9ab935a` (the runtime code of both is unchanged since the e-mail commits; the last three commits changed docs and test files only). Local stack: local Supabase (GoTrue, TOTP on), MinIO standing in for R2, fake Stripe, Mailpit as the SMTP server.

| Suite | Result | Level |
|---|---|---|
| API (`mvn clean test`, all classes) | **520 run, 0 failures, 0 errors, 7 skipped** (the 7 are opt-in: 6 hosted-R2 tests need `R2_TEST_ENABLED`, 1 search-scale probe needs `-Dsearch.perf`). Includes 62 notification tests in 5 classes (core 6, rendering fr/ht 21, real SMTP via GreenMail 7, flow 22, admin API 6) plus the payment-webhook notification tests in `OnlinePaymentIntegrationTest` | UNIT/INT |
| Web unit (`vitest`) | **329 passed**, 37 files; `tsc --noEmit` clean; ESLint 0 errors, 2 warnings (unused `_`, pre-existing) | UNIT |
| `e2e:storefront` | **145 / 0** (the first run on the final stack showed 83 / 13 because the host clock jumped ~16 h mid-run and the suite's cached 1 h staff token expired; the identical code re-run on a stable clock passed 145 / 0) | LOCAL-E2E |
| `e2e:admin` | **139 / 0** | LOCAL-E2E |
| `e2e:a11y` | **136 / 0** page scans (axe, 390 and 1280 px, fr + ht) | LOCAL-E2E |
| `e2e:payment-messaging` | **97 / 0** (capability response stubbed = MOCK; every state x fr/ht, desktop + mobile) | LOCAL-E2E / MOCK |
| `e2e:pwa` | **56 / 0** | LOCAL-E2E |
| `e2e:stripe-real` | **57 / 0** (real API, local FAKE Stripe) | LOCAL-E2E / FAKE |
| `e2e:stripe` (mocked) | **72 / 0** | MOCK |
| `e2e:notifications` | **34 / 0** (real SMTP into Mailpit: both messages per journey, per-currency totals, fr + ht, masked recipients, outage -> DEAD -> audited resend delivered once) | LOCAL-E2E |
| `e2e:team` (API with `ANA_STAFF_REQUIRE_MFA=true`) | **30 / 0** (real GoTrue TOTP) | LOCAL-E2E |
| Request latency | 34 routes, slowest p95 64 ms | MEASURED (local) |
| Search performance, 20,000 products | worst p95 107.6 ms | MEASURED (local) |

Test defects found and fixed while producing these numbers (no assertion was weakened): `R2AbandonedUploadCleanupIntegrationTest` signed its upload URL for 1 s and failed intermittently (MinIO: "Request has expired"; now a 4 s validity, same assertions); the notifications browser test asserted wording the UI never had ("une seule fois", now "qu'une fois" + "encore d'actualité") and a masked address with the wrong first letter.

**Never run against a hosted service:** Supabase, R2, Stripe (test or live), a real SMTP provider, TLS, monitoring, restore drills. Those rows stay BLOCKED until the staging environment exists.
