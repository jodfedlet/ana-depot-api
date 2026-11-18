# Hosted staging verification report

Date: 2026-11-18. Web `46f0779`, API `ba2ae53` + this change. **Hosted checks run: 0.** No staging resource exists, no credentials were supplied, nothing was deployed, no cloud resource was created or purchased.

Legend: **PASS** = verified on a real hosted staging resource · **FAIL** = ran and failed · **BLOCKED** = cannot run, prerequisite named · **SKIP** = deliberately not run. LOCAL results are in §2 and are *not* hosted evidence.

## 1. Hosted checks, in the required order

| # | Check | Status | Prerequisite (owner) | Tool ready |
|---|---|---|---|---|
| 1 | DNS and TLS (Full strict, HTTP→HTTPS, valid origin certificate, HSTS after all hosts work) | **BLOCKED** | B0 domain + Cloudflare zone | `probe.mjs` (TLS/HSTS show SKIP locally, never PASS) |
| 2 | Supabase hosted migrations V1–V14, Auth config, JWT validation, redirect URLs | **BLOCKED** | B1 staging project | API start-up runs Flyway; `FlywayMigrationIntegrationTest` defines the expected state |
| 3 | RLS lockdown / Data API access (anon cannot read or write app tables) | **BLOCKED** | B1 | migration V11 + `MigrationRlsConventionTest` (local); hosted anon-key probe to write |
| 4 | R2: presigned upload, MIME/size/key validation, metadata, public delivery via custom domain, ordering, deletion, CORS, failure paths, abandoned cleanup | **BLOCKED** | B2 bucket + key, image hostname | storage tests ran against MinIO only (not R2) |
| 5 | API health, readiness, database connection | **BLOCKED** | B1 + API runtime | `/actuator/health/{liveness,readiness}`, `probe.mjs` |
| 6 | CORS, trusted proxy, security headers | **BLOCKED** | hosts + real ingress path | `probe.mjs`, `check-env.mjs` |
| 7 | Frontend SSR and catalog (hosted Worker) | **BLOCKED** | B0, B4 deploy | OpenNext spike (§2) |
| 8 | Storefront browser tests | **BLOCKED** | 1–7 + staff test account | needs a new `e2e/staging` configuration |
| 9 | Admin browser tests | **BLOCKED** | same | same |
| 10 | PWA install and offline over HTTPS (Chrome desktop/mobile emulation; Safari/Firefox where available; no physical-device claim) | **BLOCKED** | hosted HTTPS | `e2e/pwa` is local-only |
| 11 | Cache isolation, sensitive caching, secret-leak scan of the hosted bundle | **BLOCKED** | deployed host | `probe.mjs`, `scripts/scan-bundle-secrets.mjs` |
| 12 | Sentry test exception, API errors visible, alert reaches the destination, no secrets/cookies/auth headers/payment data in logs, `staging` labels | **BLOCKED** | B5 accounts + alert destination | `report.ts` (web) exists; no vendor SDK wired yet |
| 13 | Hosted backup + controlled restore, measured time and result | **BLOCKED** | B1 (+ approval for PITR, §6 of the plan) | `dev/restore-test.sh` (local) |
| 14 | Stripe test flows (HTG/USD eligibility, sessions, signed webhooks, duplicates/out-of-order, settlement, fees) | **BLOCKED** | B3: merchant country decision + dedicated Ana Dépôt test account | integration + local fake only |
| 15 | Full 19-step hosted journey | **BLOCKED** | 1–14 | |

Stripe stays **disabled** in staging until B3: `ANA_STRIPE_ENABLED=false`, no `STRIPE_*`. The API refuses to report a payment as paid without a verified signed webhook, so no fake success can exist on a deployment that has no Stripe account.

## 2. Evidence gathered in this session (LOCAL / VENDOR DOCS — not hosted)

| Evidence | Level | Result |
|---|---|---|
| API image builds from the new `Dockerfile` | LOCAL | built (3 m 32 s cold, 664 MB), non-root |
| API image runs with `SPRING_PROFILES_ACTIVE=prod`, Flyway V1–V14 on an empty database, separate management port, actuator not on the public port | LOCAL | PASS (against local Postgres, which is not Supabase) |
| JVM start-up vs CPU: 102 s (½ vCPU) / 61 s (1 vCPU) / 18 s (2 vCPU); idle memory 366–546 MiB | LOCAL | measured; drives the Containers verdict |
| `vinext check` | LOCAL | 95 % compatible; partial: Turbopack loader rules ignored, `cacheComponents` incomplete |
| OpenNext 1.20.17 build of the unmodified app | LOCAL | builds; bundle 2.27 MiB gzip (limit 10 MiB paid) |
| OpenNext bundle on local workerd, populated cache | LOCAL | 200/404/307/401 as expected on 11 routes; setTimeout/Cache Components warning on start |
| Same bundle with a no-op or **empty** cache | LOCAL | **FAIL**: every HTML route 500 ("Worker's code had hung") — a release step (`populateCache`) is mandatory and must be smoke-tested |
| Cloudflare Containers behaviour, Supabase connectivity (IPv6 direct, IPv4 session pooler), prices | VENDOR DOCS | read, not exercised; Containers egress to Postgres, always-on and client-IP peer are unknown |
| Earlier full local suites (API 432 tests, web 295 unit, 145 storefront, 139 admin, 57+72 Stripe, 56 PWA) | LOCAL / MOCKED | all passed; they say nothing about hosted behaviour |

## 3. Evidence to preserve when hosted runs happen
URLs and UTC timestamps; commit SHAs (web, API); Cloudflare deployment ids and Docker image digest; test output; response headers with cookies/authorization removed; screenshots; restore start/end times and row-count checks; alert delivery proof (screenshot of the received notification); provider plan names and limits at the time. **No secret values, tokens, cookies, DSN secrets or card data in any evidence file.**

## 4. Production-readiness recommendation
**NO-GO for production.** Reason: zero hosted evidence for database, storage, payments, TLS, monitoring or recovery, and three unresolved architecture unknowns (API runtime egress/always-on/client IP; OpenNext Cache Components on a real Worker; RPO 5 min needs PITR and a measured restore). **GO to begin hosted staging** once B0–B2 and B5 are supplied and the spend in the plan (§6) is approved; B3 and B6 can follow without blocking the first hosted run.
