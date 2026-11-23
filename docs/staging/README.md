# Hosted staging: readiness audit and verification runbook

Status as of 2026-11-18. **Recommendation: NO-GO for production; staging verification NOT STARTED** because no hosted staging resource exists yet (see "Blockers").
This document separates what was verified **locally**, with **mocks**, on **hosted staging**, and against **live providers**. Nothing below is hosted or live evidence unless it says so.

Commits audited (both on `main`, nothing pushed, working trees clean before this task): web `ca71b84`, API `01dc143`. This staging pack was added on top in a separate commit.

**Cloudflare staging (2026-11-18):** the hosting plan, Cloudflare compatibility report (web: OpenNext vs vinext; API: Containers vs a managed runtime), measured container footprint and cost estimate are in [cloudflare-staging-plan.md](cloudflare-staging-plan.md); the per-check PASS/FAIL/BLOCKED/SKIP matrix is in [hosted-verification-report.md](hosted-verification-report.md). The API now has a `Dockerfile`.

## 1. Blockers (what only the owner can provide)

| # | Needed | Why it blocks | Who |
|---|---|---|---|
| B1 | A **dedicated staging Supabase project** (not production), its URL, publishable key, DB connection string and an application role | no hosted-Supabase verification is possible without it; the local Supabase CLI stack is explicitly not evidence | owner |
| B2 | A **dedicated staging Cloudflare R2 bucket** + a bucket-scoped access key + the public image domain | MinIO is not R2 (signature, CORS, public delivery, lifecycle all differ) | owner |
| B3 | The **Ana Dépôt Stripe account in test mode** (not the Revofin account): test secret key, a webhook endpoint secret, and the account country | currency eligibility, settlement, fees and real webhooks can only be read from that account; if the account is not set up this is a blocker | owner |
| B4 | A **staging host** for the API and the web app with HTTPS, DNS names and a reverse proxy; its proxy ranges | TLS/HSTS/CSP/CORS/cookie behaviour, PWA install and real latency exist only when deployed | owner |
| B5 | A **monitoring/error-tracking vendor decision** and an alert destination | nothing is deployed that scrapes metrics or pages anyone | owner |
| B6 | A real **terms version label** (and, separately, the legal text) | the web and API values must match; reservations are refused otherwise | owner |

I did not look for credentials on this machine (an attempt to inspect credential stores was refused by the sandbox, correctly) and I did not create any cloud resource: that costs money and touches your accounts, so it needs your explicit go-ahead.

## 2. Tools prepared in this repository (all verified LOCALLY)

| Tool | Purpose | Verified |
|---|---|---|
| `docs/staging/api.staging.env.template`, `web.staging.env.template` | every variable the two apps read, placeholders only | audited against `application.yaml` and `process.env` usage |
| `node dev/staging/check-env.mjs <api.env> <web.env>` | offline validation of the filled files: terms versions identical, CORS contains the exact storefront origin and no wildcard, Stripe key is `sk_test_`, return URL = storefront, asset origin = R2 public origin, `sslmode`, no transaction pooler, no secret in `NEXT_PUBLIC_*`, HSTS/HTTPS flags | run: templates -> 20 errors (placeholders); a complete fake pair -> 0; seven deliberate mistakes each reported |
| `node dev/staging/probe.mjs --web <url> --api <url> [--rate-limit]` | credential-free checks of the DEPLOYED responses: TLS validity/expiry/version, http->https, HSTS, CSP, CORS (3 hostile origins), 401s, alg=none token, error-body leakage, actuator exposure, private-page cache headers, robots/sitemap/404/manifest/service worker, secret-shaped values in the public JS, 429 | run locally with `--local`: all checks pass, TLS and HSTS are reported as SKIP (never as passed) |
| `dev/restore-test.sh` | repeatable dump/restore/start/compare/reconcile on a throwaway Postgres | run locally earlier (21/21); see `docs/operations.md` |
| `dev/fake-stripe.mjs` | local Stripe double for browser E2E | local only; never staging |
| web suites `e2e:storefront` (145), `e2e:admin` (139), `e2e:stripe-real` (57, fake Stripe), `e2e:stripe` (72, mocked), `e2e:pwa` (56) | browser journeys | all pass LOCALLY on a fresh stack; **not suitable to point at staging unchanged** (see section 9) |

## 3. Runbook per section (what to run when B1-B4 exist, and the evidence to keep)

### 3.1 Hosted Supabase (B1)
1. Fill `api.staging.env`; `node dev/staging/check-env.mjs`.
2. Start the API with `SPRING_PROFILES_ACTIVE=prod` against the staging DB. **Expected:** Flyway applies V1-V14 on the empty project (capture the log), `ddl-auto=validate` passes, `ProductionConfigGuard` prints its warnings (read them), health `readiness` is UP.
3. RLS and Data API lockdown (V11): with the project's **anon key**, `GET https://<ref>.supabase.co/rest/v1/<table>` for every application table must return an empty list or 401/403, never rows; and `\dp`/`pg_policies` shows RLS enabled on every table (`MigrationRlsConventionTest` proves it on plain PostgreSQL only). Newly created tables (V14: `payment_attempts`, `payment_provider_events`) are included.
4. Role ownership: the application role must own the tables or have BYPASSRLS, else it sees empty tables (`docs/database-providers.md` section 3, `docs/supabase-database.md`).
5. Auth: create a staff user and a customer in the staging project (e-mail confirmation on); set `ANA_BOOTSTRAP_ADMIN_SUBJECT`; verify `GET /api/v1/me` for each (staff true/false), a token from ANOTHER project is 401, an expired token is 401.
6. Registration/verification: sign up with a real mailbox you control; the confirmation e-mail must arrive from Supabase's sender (Supabase documents its built-in SMTP as rate-limited and not meant for production, so **a custom SMTP provider is expected before launch**; confirm in the dashboard).
7. Pooling: connect through the session pooler (`:5432`) and confirm Flyway and the 20 s transaction timeout behave; never the transaction pooler.
8. Customer isolation: two customers, each reserves while signed in; each must see only their own at `/me/reservations` and get 404 for the other's id (this is also covered by the local storefront suite).

### 3.2 Real R2 (B2)
Run the existing opt-in test: `R2_TEST_ENABLED=true mvn test -Dtest=R2RealIntegrationTest` with staging credentials (`docs/storage-r2.md`), then in the staging admin: upload a product image through the UI. **Evidence to capture:** presigned PUT succeeds from the browser (R2 **CORS** on the bucket must allow `PUT` from the staging web origin with `content-type`), `complete` verifies size/type/signature, the public URL (custom domain or r2.dev) serves the image with the right `Content-Type`, reordering and deletion remove the object (check the bucket), an oversize/forged/wrong-type upload is refused, an abandoned ticket is cleaned up after `abandoned-after` (1 h), the bucket is **not** publicly listable and the access key is scoped to this bucket only. Check the CSP: product images load with `NEXT_PUBLIC_ASSET_ORIGINS` set to the R2 public origin.

### 3.3 Stripe, Ana Dépôt test account (B3)
1. Read, with the staging test key: `GET /v1/account` (country, capabilities, default currency) and `GET /v1/country_specs/<country>`; record whether **`htg`** and **`usd`** are in `supported_payment_currencies`. Create a test-mode session in each currency with `adaptive_pricing[enabled]=false` and note any error. Record fees and conversion/settlement behaviour from the Dashboard (Balance, a test payment's balance transaction) for each currency. These are the facts the owner needs before offering HTG online.
2. Create the webhook endpoint `https://<api>/api/v1/webhooks/stripe` with the six events listed in `docs/payments-stripe.md`; set `STRIPE_WEBHOOK_SECRET`.
3. Journeys with the Stripe test card `4242 4242 4242 4242` (and the documented decline card): success, decline (attempt stays open), abandon (session expires), duplicate delivery (Dashboard "Resend"), mixed HTG+USD reservation, confirm the ledger shows exactly one `STRIPE_CHECKOUT` payment per success and `GET /admin/reservations/{id}/reconciliation` is consistent. Never use live mode.

### 3.4 Deployment security (B4)
`node dev/staging/probe.mjs --web https://<web> --api https://<api> --rate-limit` and keep the output. Additionally, by hand: HSTS preload is NOT requested (do not add it until the domain is permanent); run an SSL Labs / `testssl.sh` scan; confirm the proxy forwards `X-Forwarded-For` only from `ANA_RATE_LIMIT_TRUSTED_PROXIES`; session expiry (let a token expire, an admin screen must say "sign in again"); PWA never caches `/admin`, `/account`, `/auth`, `/payment`, `/reservations` (`e2e/pwa` and a Cache Storage enumeration on staging); dependency audit on a fresh vulnerability database (the OWASP scan used a database cached 2026-05-06).

### 3.5 Browsers and PWA
Run `e2e:pwa` logic by hand on staging over real HTTPS (install prompt needs HTTPS): Chrome desktop and Chrome mobile emulation (Playwright, available), then **Firefox and Safari/iOS by hand on real devices** (not available to automate here). Record: install, offline shell, offline cart estimate, reconnect refresh, update prompt after a second deploy (`PWA_VERSION` differs), sign-out purges caches. Do not report physical-device verification unless a person did it on a device.

### 3.6 Recovery and monitoring (B1, B5)
See section 5 for the achievability assessment. Verify on the hosted project: backups enabled and listed, one real **restore into a new/branch project** (not over staging data you need), timed; then `dev/restore-test.sh`-style checks (row counts, ledger reconcile, V11 lockdown). Application rollback: redeploy the previous image and confirm Flyway refuses nothing (migrations are forward-only: expand/contract, never `flyway repair`). Monitoring: point the scraper at the private management port, import the alert rules from `docs/operations.md`, then **trigger** one (stop the DB connection, or send a burst of 5xx) and see the notification arrive; do the same for the frontend error hook.

## 4. What the local evidence does and does not cover

| Area | Local | Mocked | Hosted staging | Live |
|---|---|---|---|---|
| Flyway V1-V14, `ddl-auto=validate`, RLS/lockdown on plain PostgreSQL and Supabase's Postgres image | yes | - | **not done** | no |
| Supabase Auth (GoTrue) sign-up/sign-in/verification | yes (local CLI stack) | stub suite | **not done** | no |
| JWT verification (issuer/audience/alg) | yes (local keys) | - | **not done** (JWKS fetch from the hosted project) | no |
| Image upload, public delivery | yes (MinIO) | - | **not done** (real R2) | no |
| Stripe checkout, webhooks, ledger, mixed currency | yes (local fake Stripe, signed webhooks) | yes | **not done** | no (test-mode API only: account NOT Ana Dépôt's) |
| Security headers/CORS/CSP/XSS/forged tokens/uploads | yes (76 attack checks, probe) | - | **not done** (TLS/HSTS untestable locally) | no |
| PWA | yes (Chromium) | - | **not done** (needs HTTPS; no Safari/Firefox/phone) | no |
| Backup/restore | yes (pg_dump/restore, 21/21, 125 s) | - | **not done** (hosted backups/PITR) | no |
| Monitoring/alerts | metrics exist, rules written | - | **not deployed** | no |

## 5. RPO 5 min / RTO 4 h: achievable?

From Supabase's own documentation (read 2026-11-18): daily backups exist for Pro/Team/Enterprise only (7/14/30 days) -> **worst-case data loss up to 24 h, so RPO 5 min is NOT achievable with daily backups**. Point-in-Time Recovery is an add-on (Pro and above); it archives WAL **every two minutes by default** (sooner under load), and enabling it **replaces** daily backups. **RPO 5 min is therefore plausible only with PITR enabled** (a ~2-minute archive interval leaves headroom; the "latest restore point can be well behind when the database is idle" caveat does not lose data). A restore makes the project **inaccessible** for a time that "depends on the size of the database"; for this small database 4 h should be comfortable, but **no hosted restore has been timed**, so RTO 4 h is a hypothesis until step 3.6 is done. R2 objects are outside the database backup: an object deleted from the bucket is not restored by PITR (use R2 versioning/lifecycle or accept loss and re-upload; `docs/operations.md` describes DB<->object reconciliation). Cost of PITR retention is per-day-of-retention (see Supabase's pricing page): the owner must approve it.

## 6. Findings so far (none are hosted findings)

| Severity | Finding | Status |
|---|---|---|
| High (process) | No hosted staging exists; production-readiness cannot be claimed | blocker B1-B5 |
| Medium | `script-src 'unsafe-inline'` in the CSP (nonces would force dynamic rendering and defeat the server-rendered SEO pages) | accepted, documented in `docs/security.md` |
| Medium | Production build needs the API reachable at build time (pages prerender with live catalog data) | deployment requirement: build in an environment that can reach the staging API |
| Medium | Supabase's built-in e-mail sender is not for production traffic | needs custom SMTP before launch (owner) |
| Low | Auth cookies are not HttpOnly (shared browser/server session design); no token revocation before expiry; one staff role, no MFA | accepted/open, business decision |
| Low | `npm audit` shows 17 dev-only findings whose fixes are major downgrades | `npm audit --omit=dev` is 0 |
| Info | Creole strings unreviewed by a native speaker | owner |

## 7. Business decisions still open (not invented anywhere)

Refund policy and who may refund · partial payments · overpayment · whether to allow online prepayment before READY_FOR_PICKUP · reservation hold period/expiry · customer cancellation after ready · staff roles/permissions (and MFA) · wholesale visibility · legal terms text and version · notification channel · units/pack conversion · low-stock policy · HTG online settlement and fees · native Haitian Creole review · domain names · monitoring vendor · RPO/RTO approval and PITR budget.

## 8. Go / no-go

**NO-GO for production.** Reasons: hosted Supabase, real R2, the Ana Dépôt Stripe account, HTTPS/TLS behaviour, real-device PWA, hosted backup/restore and deployed monitoring are all unverified; the business policies above are undecided. **GO for starting staging** as soon as B1-B4 are provided: the code, tests, templates and probes are ready, and every item above has an exact procedure.
