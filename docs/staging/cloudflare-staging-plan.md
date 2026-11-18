# Cloudflare staging: plan, compatibility report, costs

Status: **PLAN + LOCAL EVIDENCE ONLY. Nothing is deployed and no cloud resource exists.** Written 2026-11-18 against web `46f0779` (audited `ca71b84`) and API `ba2ae53` plus the `Dockerfile` added with this document.
Production remains **NO-GO**. Hosted results are in [hosted-verification-report.md](hosted-verification-report.md) (all hosted checks BLOCKED).

Evidence levels used throughout: **LOCAL** (this developer machine, local Supabase/MinIO/fake Stripe, local workerd), **VENDOR DOCS** (read on 2026-11-18, not exercised), **HOSTED** (a real staging resource; none yet).

## 1. Decisions needed from the owner, and what each one unlocks

| # | Owner provides | Never in chat/Git | Unlocks |
|---|---|---|---|
| B0 | Domain ownership of `anadepot.com` (or the real domain) and a Cloudflare account/zone for it; approval to buy the paid plans in §6 | | every hosted step; DNS is not created from guessed targets |
| B1 | Dedicated staging Supabase project: project ref, URL, publishable key (all public identifiers) | DB password, runtime role password, any secret/service-role key | hosted DB, Auth, RLS |
| B2 | Dedicated R2 staging bucket **name**, public image hostname | R2 access key id/secret | upload/delivery checks |
| B3 | Stripe merchant country decision and a dedicated Ana Dépôt test account | `sk_test_…`, `whsec_…` | online-payment checks (stays DISABLED until then) |
| B5 | Sentry org/projects and New Relic account created by the owner; one alert destination (address or channel) | DSNs are not secret but license/ingest keys are | monitoring checks |
| B6 | An approved terms **label** after the legal text is reviewed (proposed `ana-depot-terms-2026-11-v1`, not applied) | | reservation flow (both repos must carry the same value) |

**How secrets reach the deployment without chat, terminal output, files or Git:** the owner types them directly into the provider's secret store (`wrangler secret put`, the Cloudflare dashboard, the Supabase dashboard). I only need the *names* of the secrets and non-secret identifiers. For checks that need a staff login I need a dedicated staging test account; the owner can export its credentials into their own shell for the test run, and I will not print or store them. I will not read local credential stores.

## 2. Architecture and recommendation

```
Browser ──► Cloudflare edge (DNS, TLS Full strict, HSTS, WAF/rate rules)
              ├─ staging.<domain>        ─► Worker (Next.js via OpenNext)      [§3]
              ├─ api-staging.<domain>    ─► API runtime (Spring Boot image)    [§4]
              └─ images-staging.<domain> ─► R2 public bucket (custom domain)
Supabase staging project: PostgreSQL (session pooler :5432 / direct) + Auth
```

| Piece | Recommendation | Confidence |
|---|---|---|
| Web | **OpenNext Cloudflare adapter**, not vinext (reasons in §3) | medium: renders locally on workerd; hosted behaviour with real data is unproven |
| API | **Do not commit to Cloudflare Containers yet.** Test it as option A (needs paid plan, §6); prepare **option B: a managed container runtime with an always-on instance behind the Cloudflare proxy** | low: three blocking unknowns (§4.3) |
| Database | Supabase staging, session pooler `:5432` (IPv4) unless the runtime has IPv6 and then direct | high (vendor docs) |
| Images | R2 custom domain; cache bucket for Next is a *separate* private bucket | high |

## 3. Frontend compatibility (Next.js 16.4.6 on Workers)

### 3.1 What the app uses (inspected in the repo)
Next 16.4.6, App Router, **`cacheComponents: true` + `partialPrefetching`**, one `"use cache"` function (`src/lib/api/server-catalog.ts`, `cacheLife` 60 s/1 h, `cacheTag("catalog")`), `revalidateTag` in `POST /api/revalidate-catalog`, **`src/proxy.ts`** (Node-runtime proxy: locale redirect, 404 rewrite that calls the API, Supabase session refresh), 3 route handlers (`/sw.js`, `/api/revalidate-catalog`, auth callback), no Server Actions, `next/image` only with `unoptimized` remote images, Supabase SSR cookies via `next/headers`, Tailwind through a **Turbopack loader rule** (`@tailwindcss/turbopack`), `headers()`/`redirects()` in `next.config.ts`.

### 3.2 vinext vs OpenNext, with evidence
| Question | vinext | OpenNext (`@opennextjs/cloudflare` 1.20.17) |
|---|---|---|
| Cloudflare's current guidance (docs, updated 2026-11-05) | Recommended default; labelled beta | For existing apps that cannot move to vinext |
| `npx vinext check` on this app (LOCAL) | **95 %: 20 supported, 2 partial, 0 issues.** Partial: `turbopack` ("rules (loaders) are ignored") and `cacheComponents` ("experimental support; behavior is incomplete") | n/a |
| Tailwind | The app's CSS pipeline is a Turbopack loader rule. vinext ignores it, so the CSS toolchain would have to be rewritten first | Unchanged (it wraps a normal `next build`) |
| `cacheComponents` / `"use cache"` | Cloudflare itself says full behaviour is incomplete | Listed as supported; open issues on 16.3.8/16.4 report hangs and 500s (#1450, #1458, #1464, #1369) |
| `proxy.ts` | Supported | "Experimental since 1.20.3"; build prints *"Node.js middleware support is experimental … not officially maintained by OpenNext maintainers"* |
| Migration cost | Port the build toolchain | Config files only (`open-next.config.ts`, `wrangler.jsonc`) |

**Recommendation: OpenNext**, because vinext would change the CSS pipeline and relies on the feature (`cacheComponents`) it documents as incomplete, while OpenNext needs no app changes. Both carry an explicit risk on the same feature; that risk can only be closed with real data on a hosted Worker. No migration was performed on the repo; this is a spike in a scratch copy outside Git.

### 3.3 Local spike results (LOCAL, workerd via `wrangler dev`, no Cloudflare account, simulated bindings)
- `opennextjs-cloudflare build` **succeeds** (1 m 38 s) on the unmodified app; `/sw.js` is prerendered at build, so its build-time `fs` read is *not* a Workers problem (an earlier worry in this audit; corrected).
- Bundle: wrangler `--dry-run` **Total Upload 11 941 KiB / gzip 2 322 KiB (≈ 2.27 MiB)** — under the 3 MiB (free) and 10 MiB (paid) gzip limits.
- First two attempts returned **500 on every HTML route** ("Worker's code had hung") with the adapter's default no-op cache and with an *empty* R2 cache. After `opennextjs-cloudflare populateCache` (the documented step that loads the prerendered output into the cache bucket) every route works. The failure was my test setup, but it shows **deployment fails hard if the cache bucket is not populated**; the deploy pipeline must run `populateCache` and the smoke test must fail the release on any 5xx.
- With the populated cache: `/fr`, `/ht`, `/fr/search`, `/fr/auth/login`, `/fr/info/terms` → 200 (≈ 0.04–0.8 s); unknown product/category → **404** (proxy rewrite works on the Node-proxy path, and the `"use cache"` fetch to the API ran); `/` → 307 by `Accept-Language`; legacy `/login` redirect; `POST /api/revalidate-catalog` without token → 401; sitemap/robots/manifest/`sw.js` → 200; CSP, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, `nosniff` present; page headers `Cache-Control: private, no-store` on auth pages.
- Warning on every start: *"Next.js cannot guarantee that Cache Components will run as expected due to the current runtime's implementation of setTimeout()."* This is the central open risk.
- **Not tested:** real products (catalog was empty), staff/customer login with Supabase cookies, on-demand `revalidateTag` (needs the tag cache below and a staff token), reservation POST from the Worker, Safari/Firefox, performance under load.

### 3.4 What a hosted deployment needs (from OpenNext docs; not yet configured)
`wrangler.jsonc` with `nodejs_compat`, compatibility date ≥ 2024-09-23, assets binding, an R2 **incremental-cache bucket (private, separate from the image bucket)**, a **tag cache** (D1 or sharded Durable Object) because the app calls `revalidateTag`, a **Durable Object queue** for time-based revalidation (`cacheLife`), `WORKER_SELF_REFERENCE`. Cache purge needs a zone token and zone id. Memory queue is staging-only.

### 3.5 Build-time dependency on the API
`next build` prerenders catalog pages from the live API. Pipeline order is therefore: Supabase → API up and migrated → **web build against `api-staging`** → `populateCache` → deploy. The build must run where it can reach the staging API and must carry staging values only. Never point it at a production API or database.

## 4. API compatibility (Spring Boot 4.1.2, Java 25)

### 4.1 What existed
There was **no Dockerfile**. This change adds `Dockerfile` + `.dockerignore`: multi-stage (Maven 25 build, layered jar, `eclipse-temurin:25-jre`), non-root uid 10001, `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, no secrets baked in, tests not run in the image build. Image size 664 MB (LOCAL). The application is stateless (uploads → R2, state → PostgreSQL); graceful shutdown (30 s) and liveness/readiness groups already exist (`/actuator/health/liveness|readiness`, served on the private management port when `MANAGEMENT_SERVER_PORT` is set).

### 4.2 Measured footprint (LOCAL: image run with `--network host`, prod profile, local Postgres, empty schema, Flyway V1–V14 applied in 0.7 s)

| Container limit | Start to "Started" | Wall to readiness 200 | Idle memory |
|---|---|---|---|
| ½ vCPU, 4 GiB (Cloudflare `standard-1` shape) | 102 s | 108 s | 424 MiB |
| 1 vCPU, 1 GiB | 61 s | 66 s | 366 MiB |
| 2 vCPU, 8 GiB (`standard-3` shape) | 18 s | 20 s | 546 MiB |

The management port is separate (actuator → 404 on the public port, as designed). These are CPU-throttled Docker numbers on this machine; Cloudflare vCPUs may behave differently, so treat the shape of the curve (startup is CPU-bound, memory is not) as the finding.

### 4.3 Cloudflare Containers assessment (VENDOR DOCS; nothing exercised)
| Requirement | Finding |
|---|---|
| Reachability | A container "can only be accessed through its Durable Object" via a Worker; no direct TCP/UDP. So every API request crosses Worker → DO → container |
| Memory/CPU | Up to 4 vCPU / 12 GiB / 20 GB (`standard-4`); `standard-2` (1 vCPU, 6 GiB) is the smallest comfortable JVM shape |
| Cold start | Platform says 1–3 s for the container, **but the JVM adds 18–102 s** (§4.2). Scale-to-zero would make the first customer/webhook after idle wait up to ~1 min; always-on is **not documented** on the pages read |
| Health checks | Only port readiness is documented, no HTTP health-check configuration. Readiness gating has to be done in the Worker |
| Disk | Ephemeral: acceptable (stateless app) |
| Shutdown | SIGTERM, up to 15 min grace: compatible with our 30 s drain |
| Egress to PostgreSQL | **UNKNOWN.** The pages read say nothing about outbound TCP on :5432, IPv4/IPv6 or static IPs. Supabase's direct host is IPv6-only unless the paid IPv4 add-on is on; the shared session pooler is IPv4. This must be tested from a real container before anything else |
| Client IP / rate limiting | The API trusts `X-Forwarded-For` only when the immediate peer is in `ana.rate-limit.trusted-proxies`. The peer seen inside a container behind a Worker/DO is unknown, and **I will not invent ranges**. Method: deploy, send requests from a known client, log what `ClientIpResolver` sees, then set the list from that observation |
| Scaling | One DO ↔ one container; horizontal scale needs a Worker-side balancer. Each instance holds a Hikari pool of 10, against the Supabase connection cap |
| Maturity | GA/beta status of Containers is **not stated** on the pages read; Snapshots are described as public beta |

**Verdict: UNDECIDED, not incompatible.** The blockers are always-on behaviour, outbound TCP to Supabase and the proxy peer address. If any fails, the documented fallback is **option B: a managed container runtime with a minimum of one always-on instance (≥ 1 vCPU, ≥ 2 GiB), IPv4/IPv6 egress to Supabase, HTTP health checks, behind the Cloudflare proxy with Full (strict) TLS and an origin certificate.** I will not force Containers.

## 5. TLS, network, headers (design; HOSTED verification pending)
Full (strict) with an origin certificate on the API runtime; "Always Use HTTPS" on; HSTS enabled in the app (`ANA_HSTS_ENABLED=true`, `SECURITY_HTTPS_ONLY=true`) **only after all three hostnames serve valid HTTPS**. Both apps already send `max-age=63072000; includeSubDomains` (2 years, no `preload`); browsers cache that per host, so a staging host that later loses HTTPS stays unreachable over HTTP for up to 2 years. Do **not** add `preload`, and ask the owner whether staging should use a shorter `max-age` (it is hard-coded in `SecurityConfig` and `src/lib/security/headers.ts`, so changing it is a code change, not made here); exact CORS (`ANA_CORS_ALLOWED_ORIGINS=https://staging.<domain>`), no wildcard; CSP as shipped (`unsafe-inline` for scripts/styles is a **known unresolved risk**: Next inline bootstrap scripts need a per-request nonce, which would make pages dynamic and conflict with prerendering/PPR; documented in `docs/security.md`); `check-env.mjs` and `probe.mjs` run before and after.

## 6. Cost estimate (USD/month, staging, calculated from published prices, not a quote)

| Item | Basis | Estimate |
|---|---|---|
| Cloudflare Workers Paid | required for Containers; likely needed for SSR CPU limits (unverified) | $5 |
| API on Cloudflare Containers, always-on `standard-2` (1 vCPU/6 GiB/12 GB) | memory $0.0000025/GiB-s + disk $0.00000007/GB-s provisioned, CPU $0.00002/vCPU-s active only (≈ 5 % busy assumed); 1 TB egress included | ≈ $44 (≈ $60 for `standard-3`); business-hours-only ≈ $15–20 |
| API on a managed runtime (option B) | **not quoted**: owner picks vendor | to be quoted |
| R2 (images + Next cache) | $0.015/GB-mo, free 10 GB, 1 M Class A, 10 M Class B, no egress fee | $0 at staging volume |
| Supabase Pro (+ Micro compute covered by the included $10 credit) | $25/mo | $25 (≈ $30 with a Small compute at $15 less credit) |
| Supabase IPv4 add-on | only if the runtime needs the direct connection; not needed with the session pooler | $0 (or $4) |
| Supabase PITR, 7 days | needed to approach RPO 5 min; replaces daily backups; **not enabled without approval** | $100 (docs' own 730 h example); a 48 h restore drill ≈ $7 if billed hourly (confirm) |
| Sentry | Developer plan free (5 000 errors, 1 user); Team $26–29 if more users needed | $0 (or ≈ $26–29) |
| New Relic | free tier 100 GB/mo, 1 full user; overage $0.40/GB | $0 |
| Stripe test mode | | $0 |
| Domain, transactional SMTP | owner / vendor not chosen | unknown |
| **Total** | Containers, no PITR | **≈ $75–95** (≈ $175–195 with PITR for the whole month) |

## 7. Environment configuration report (sanitized)
No values exist yet. The templates [`api.staging.env.template`](api.staging.env.template) and [`web.staging.env.template`](web.staging.env.template) cover every variable the code reads; they were **not changed** (nothing in this audit contradicted them). For the Worker, secrets go in `wrangler secret`, non-secret values in `vars`; `NEXT_PUBLIC_*` are inlined at build, so they belong in the build environment, not Worker secrets. Stripe stays **disabled**: `ANA_STRIPE_ENABLED=false`, `STRIPE_*` unset, so no payment can be reported as succeeded and pay-at-pickup is unaffected. Flyway should use separate migration credentials (`SPRING_FLYWAY_URL/USER/PASSWORD`, session-mode or direct) from the runtime application role. `node dev/staging/check-env.mjs <api.env> <web.env>` must report 0 errors before every deploy.

## 8. Unresolved risks and decisions (not resolved here, none invented)
Containers: always-on, egress to Postgres, client-IP peer. OpenNext: `cacheComponents` hangs reported upstream; Node proxy experimental; unverified revalidation. CSP `unsafe-inline`. Auth cookies not HttpOnly; no staff MFA; a single staff role. Haitian Creole copy awaiting native review. Build needs the API. SMTP provider and deliverability (Supabase built-in e-mail is not for production). RPO 5 min needs PITR and a measured hosted restore; RTO 4 h unmeasured. R2 objects are not covered by database backups (bucket versioning/replication/export is a separate decision). Stripe merchant country, HTG presentment/settlement and fees unknown. Refund, partial payment, overpayment, reservation expiry, customer cancellation, wholesale visibility and legal text are open business decisions.

## 9. Next step after approval
1. Owner approves §6 spend and supplies B0–B2/B5 (B3, B6 can follow).
2. Create nothing until named; then: DNS and certificates → Supabase migrations and Auth → R2 → API runtime test (Containers first, fallback B) → web deploy → `probe.mjs` → browser suites with a new `e2e/staging` configuration (the local suites create data, relax rate limits and use fake Stripe, so they cannot be pointed at staging unchanged) → monitoring tests → restore drill.
