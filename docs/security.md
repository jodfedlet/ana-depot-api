# Security (API + web)

What is protected, how, what was **actually verified**, and what must still be done on the hosted project. Companion documents: [auth.md](auth.md) (identity vs staff authorization),
[storage-r2.md](storage-r2.md) (uploads), [adr/0008-rate-limiting.md](adr/0008-rate-limiting.md), [supabase-database.md](supabase-database.md) (Data API lockdown), and for the web
app `ana-depot-web/docs/authentication.md`.

**Verification labels.** *Automated* = in the Maven/Vitest suites. *Local stack* = run by hand against the real Spring Boot jar, a local Supabase CLI stack (GoTrue, Postgres) and MinIO standing in for R2.
**Nothing here was verified against a hosted Supabase project, real Cloudflare R2, or over TLS.** Those are listed under "Not verified".

## 1. Controls

### API (Spring Boot, stateless bearer-token JSON API)
| Area | Control | Where | Evidence |
|---|---|---|---|
| Authentication | Supabase JWT: signature (ES256/RS256 via JWKS; `none`/HMAC refused), issuer, audience, expiry; missing issuer ⇒ every token rejected | `SupabaseJwtDecoders` | Automated (`SecurityIntegrationTest`: forged key, tampered, expired, wrong audience/issuer, `none`); local stack: 9 forged/expired/wrong-issuer/wrong-audience/HS256/`none` tokens ⇒ 401 on `/admin` and `/me` |
| Authorization | Identity ≠ staff: `STAFF` only for an active `staff_members` row; token metadata ignored; `/api/v1/admin/**` deny-by-default | `SecurityConfig`, `StaffAuthoritiesConverter` | Automated; local stack: a real customer token gets 403 on 7 admin routes (incl. upload tickets) |
| Response headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cache-Control: no-store` on every response (success, 401, 400, 404) | `SecurityConfig.responseHeaders` | Automated (`SecurityHeadersIntegrationTest`); local stack on real responses |
| HSTS | `ANA_HSTS_ENABLED=true` sends `Strict-Transport-Security: max-age=63072000 ; includeSubDomains` even behind a TLS-terminating proxy (the app sees plain HTTP there). Off by default | `HeaderProperties` | Automated (`HstsHeaderIntegrationTest`). **Not verified over real TLS** |
| CORS | Exact origins from `ANA_CORS_ALLOWED_ORIGINS`, no wildcard, no credentials, only the methods/headers the API uses; none configured ⇒ browsers refused | `CorsProperties`, `SecurityConfig.corsSource` | Automated (`CorsIntegrationTest`); local stack: foreign origin, `Origin: null`, suffix look-alike ⇒ no `Access-Control-Allow-Origin` |
| CSRF | Not applicable: no cookies, no sessions; authentication is the `Authorization` header (CSRF disabled for that reason) | | Reviewed |
| Rate limits | Postgres-backed fixed windows per operation and client (user id, else IP): `reservation.submit` 10/10 min, `reservation.lookup` 20/10 min (+30/10 min per reference), `catalog.search` 60/min, `account.write` 20/10 min, `upload.request` 30/min, `admin.write` 240/min, `admin.reindex` 3/10 min, `webhook.receive`, catalog reads 300/min. `X-Forwarded-For` honoured only from `ana.rate-limit.trusted-proxies` | `RateLimitFilter`, ADR 0008 | Automated (`RateLimitIntegrationTest`: limit, per-client independence, cross-instance, concurrency, forged `X-Forwarded-For`); local stack: real 429 with `Retry-After` and `rate_limited` problem body |
| Uploads | Staff only; type allow-list (JPEG/PNG/WebP — no SVG/GIF/HTML), size limit (default 5 MiB), server-chosen object key (never from the client), presigned URL TTL 10 min, content-length bound into the signature, **completion inspects the stored object** (size + magic bytes vs declared type) and deletes mismatches, other staff/products look like 404, abandoned uploads cleaned up | `ImageUploadService`, ADR 0007 | Automated (`UploadLifecycleIntegrationTest`, `AdminProductImageApiIntegrationTest`); local stack: 5 refused types, oversize, negative size, HTML bytes declared as PNG ⇒ stored by MinIO but completion `upload_signature_mismatch` and never attached, over-long body refused by the store (403), signed URL cannot be re-pointed at another key (403) or used for GET (400) |
| Injection | JPA with bound parameters everywhere; the single native query (`ProductSearchNative`) is built from fixed fragments with bound values and runs through `EntityManager`; search text is passed to `websearch_to_tsquery` (operators cannot break the query). No `JdbcTemplate` anywhere | | Reviewed; local stack: 8 hostile `q` values and 5 hostile filters never produce a 5xx |
| SSRF / URL fields | The server never fetches a URL supplied by a client. `imageUrl` on categories is stored only if it is a plain absolute `https://` URL (≤500 chars, a host, no credentials, no whitespace/quotes/angle brackets/backslash, no fragment) | `CategoryDraft.isSafeImageUrl` | Automated (`CategoryImageUrlTest`) |
| Enumeration | Unknown reference and wrong phone give the identical 404 (only `correlationId` differs); another person's reservation id is a plain 404 | | Automated; local stack |
| Errors | RFC 9457 problems with stable codes; no stack traces or framework names; malformed JSON ⇒ 400 | | Local stack |
| Logs | Structured JSON; request line logs method/path/status/duration only (no query string, no headers, no body); secrets masked in the storage-config line; personal-data records redact `toString()` | | Local stack: after the attack run, the captured log contained no `Authorization`/`Bearer`/JWT/password/signed-URL (`X-Amz-*`)/phone/e-mail |
| Actuator | Only `health` is public; `metrics`/`prometheus` need STAFF (and should additionally sit on a private management port, see `operations.md`); `env`, `heapdump`, `beans`, `mappings`… not reachable | | Local stack |
| API docs | `springdoc` api-docs/UI disabled in every profile; the Swagger UI bundle is not on the classpath | `pom.xml` | Dependency scan |
| Database | The Supabase Data API is locked down (migration V11); the app uses a dedicated least-privilege role; no secret in the repository | ADR 0013 | Automated (`DataApiLockdownIntegrationTest`) |

### Web (Next.js)
| Area | Control | Where | Evidence |
|---|---|---|---|
| CSP | Built from the deployment config: `default-src 'self'`; `connect-src` = self + Supabase + API + asset origins (the asset origins are needed because the service worker fetches images with `fetch()`); `img-src` = self, `data:`, `blob:`, asset origins; `worker-src`/`manifest-src` self; `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`, `frame-src 'none'`; `upgrade-insecure-requests` when HTTPS-only | `src/lib/security/{csp,headers}.ts` | Automated (17 tests); local browser: **zero violations** across 12 storefront pages, sign-in, 10 admin pages, images from MinIO with the service worker active; and the policy is *enforced* (fetch/img/iframe/script to a foreign origin each blocked and reported) |
| Other headers | `X-Content-Type-Options`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` (camera, microphone, geolocation, payment, usb… off), `X-Frame-Options: DENY`, `Cross-Origin-Opener-Policy: same-origin`, HSTS when `SECURITY_HTTPS_ONLY=true`; `Cache-Control: private, no-store` on `/admin`, `/account`, `/auth`, `/reservations/*` | `next.config.ts` `headers()` | Local browser/curl |
| Known trade-off | `script-src 'self' 'unsafe-inline'`: Next's inline bootstrap scripts without a per-request nonce, because a nonce forces every page to render dynamically and defeats the static/cached HTML the SEO and performance requirements depend on. Compensated by: no third-party scripts, React escaping, **no `dangerouslySetInnerHTML` except the JSON-LD block (escapes `<`, `>`, `&`, U+2028/9)**, `object-src 'none'`, closed `connect-src`, `form-action`/`base-uri` self. `'unsafe-eval'` only in development | | Reviewed |
| XSS | Seller/customer text is rendered by React (escaped). A product named `<img src=x onerror=alert(1)>"><script>…` (name, description and brand) was inert on the storefront (fr/ht), search results, admin list, admin form and inventory: no script ran, no element injected, no CSP violation; in HTML it appears entity-escaped (`&lt;`) and in the flight data as `<` | | Local browser |
| Open redirect | `next=` accepted only if it is a same-site relative path (no `//`, backslash or control characters); the auth callback redirects with a relative `Location` | `lib/auth/safe-next.ts`, callback route | Automated |
| Cookies | Supabase session cookies: `SameSite=Lax`, `Path=/`, **`Secure` whenever the site is HTTPS** (`SECURITY_HTTPS_ONLY` on the server, page protocol in the browser). **Not HttpOnly by design**: the browser client reads the session from the cookie, so XSS defences (CSP, escaping) are the control | `lib/auth/cookie-options.ts` | Automated (options); not observed over TLS |
| CSRF | The only cookie-authenticated route is `/auth/callback` (GET): it exchanges a PKCE one-time code (bound to a verifier cookie of the same browser, so a forged link cannot log a victim into the attacker's account) or a `token_hash`. All data calls are bearer-token calls to the API. No state-changing cookie-authenticated endpoint exists | | Reviewed |
| Secrets in bundles | Build guard refuses a secret/service-role key in a `NEXT_PUBLIC_*` variable; `node scripts/scan-bundle-secrets.mjs [literals…]` scans `.next/static` and `.next/server` for Supabase secret keys, JWTs, DB/JDBC URLs, key ids, private keys | `next.config.ts`, `scripts/scan-bundle-secrets.mjs` | Local: 2,255 files scanned, nothing found (also the local secret key and staff password as literals) |
| Dependencies | `npm audit --omit=dev`: **0 vulnerabilities**. `npm audit`: 17 findings, all in dev tooling (vitest/vite/picomatch/tinyglobby/eslint-config-next); the offered fixes are semver-major *downgrades* (`vitest@3.1.4`, `eslint-config-next@14`), so they were **not** applied | | Local |

### Both repositories
* No secret in Git: tracked-file and full-history search (Supabase secret keys, JWTs, AWS-style ids, private keys, the local DB/MinIO passwords) found nothing in either repo (27 and 32 commits at the time); `.env`, `.env.*` (except `.env.example`) and `*.pem` are ignored.
* Java dependency scan (OWASP dependency-check 12.2.2, 102 artefacts): after removing the unused Swagger UI bundle (it shipped a DOMPurify with advisories), **0 known vulnerabilities**. **Caveat:** the NVD snapshot used was the cached database dated 2026-05-06 (no automatic update was run), so newer advisories are not covered. Re-run against a fresh database before release:
  `mvn org.owasp:dependency-check-maven:12.2.2:check` (set `NVD_API_KEY` for the update).

## 2. Verified locally vs not verified

**Verified (local stack).** Everything marked "local stack"/"local browser" above: forged/expired/wrong-issuer/wrong-audience/HS256/`none` tokens, customer token on admin routes, CORS negatives, real response headers, hostile search/filter input, enumeration, forged and oversized uploads, 429 with `Retry-After`, XSS payloads inert, CSP enforced with zero violations in normal journeys, log hygiene.

**Not verified.**
* Hosted Supabase project behaviour (JWKS rotation, token lifetime, redirect URL allow-list, e-mail templates, auth rate limits, MFA).
* Real Cloudflare R2 (presigned PUT semantics, bucket CORS, public domain, lifecycle).
* TLS: HSTS, `Secure` cookies and `upgrade-insecure-requests` were only unit/integration-tested, never seen on an HTTPS deployment.
* Behind a real reverse proxy / CDN: `trusted-proxies`, forwarded headers, header stripping.
* No external penetration test, no DAST/SAST scan, no load/DoS testing.

## 3. Hardening checklist for the hosted deployment
**Supabase project**
- [ ] Confirm e-mail confirmation is **on** for sign-up; decide whether public sign-up stays open (customers) — staff are never created by sign-up.
- [ ] Set the **Site URL** and the **redirect URL allow-list** to the exact storefront origins (no wildcards); this protects `/auth/callback?next=`.
- [ ] Short access-token lifetime (≤ 1 h); the API validates offline, so revocation = expiry. Rotate JWT signing keys only with the JWKS URL unchanged.
- [ ] Enable leaked-password protection and a sensible minimum password length; set auth rate limits; configure a real SMTP provider.
- [ ] Offer MFA to staff accounts (the API has no role/permission model yet — an open business decision).
- [ ] Data API / PostgREST: keep it disabled or locked down (V11); the application connects with the dedicated `ana_depot_app` role only, over the pooler with TLS (`sslmode=require`).
- [ ] The service-role/secret key must exist only in a secrets manager, never in `NEXT_PUBLIC_*`, the web build or the repo.

**Cloudflare R2**
- [ ] The API token is scoped to **one bucket**, object read/write only; keep it in the API's secret store (never in the web app).
- [ ] Public access only through the dedicated public domain/`R2_PUBLIC_BASE_URL`; set the same origin as `NEXT_PUBLIC_ASSET_ORIGINS` in the web build (CSP).
- [ ] Bucket CORS: allow only the **admin/storefront web origin(s)**, method `PUT`, the headers the ticket returns (`Content-Type`), no wildcard origin.
- [ ] Lifecycle rule for abandoned multipart/pending prefixes as a backstop to the API's own cleanup.

**API runtime**
- [ ] `ANA_CORS_ALLOWED_ORIGINS` = exact production origin(s); `ANA_HSTS_ENABLED=true`; TLS terminated in front, plain HTTP not reachable.
- [ ] `ana.rate-limit.trusted-proxies` = your proxy/CDN CIDRs **only** (empty if none; otherwise client IPs cannot be trusted). Remember the relaxed local limits are not production values.
- [ ] Management endpoints (`/actuator/prometheus`, `/actuator/metrics`) on a private port/network, not the public listener.
- [ ] `ANA_BOOTSTRAP_ADMIN_SUBJECT` removed after the first deployment; no default accounts.
- [ ] Run dependency-check with a fresh NVD database and `npm audit` in CI; Dependabot/Renovate for both repos.

**Web runtime**
- [ ] Build with `NEXT_PUBLIC_ASSET_ORIGINS` (otherwise production `img-src` widens to `https:` and the build warns) and run with `SECURITY_HTTPS_ONLY=true` (HSTS, upgrade-insecure-requests, Secure cookies). Both are read at build **and** at runtime for the cookie flag, so set them in both places.
- [ ] Optional first rollout: `SECURITY_CSP_REPORT_ONLY=true` to watch for violations on real traffic, then enforce.
- [ ] Add HSTS preload only after the domain is permanently HTTPS.

## 4. Known limitations and open items
* **CSP uses `'unsafe-inline'` for scripts** (see trade-off above); moving to nonces/hashes is possible later at the price of dynamic rendering.
* **Auth cookies are not HttpOnly** (required by the shared browser/server session design).
* **No token revocation** before expiry (`jti` deny-list not built); staff removal is immediate because membership is read per request.
* **No staff roles/permissions or MFA enforcement** (undecided business rule).
* **All API responses are `no-store`**, public catalog reads included (safe default). If a CDN is added in front of the public catalog, set explicit `Cache-Control` on those GET endpoints deliberately.
* `GET /api/v1/admin/**` reads are not rate-limited (authenticated staff only); writes are.
* The 6 moderate/11 high `npm audit` findings are in development tooling only; revisit when `vitest`/`eslint-config-next` publish compatible fixes.
* The service worker re-fetches cross-origin images only for the configured asset origins; if the asset origins are not configured in production the CSP fallback (`img-src https:`) lets pages show images, but `connect-src` stays closed, so the worker cannot cache them (configure `NEXT_PUBLIC_ASSET_ORIGINS`).
