# Local Supabase stack (development and verification)

This is a **local, self-hosted** Supabase stack started with the official Supabase CLI (Docker). It runs the same Auth (GoTrue), PostgREST and Postgres
software as the hosted service, but it is **not** the hosted service: dashboard settings, SMTP delivery, hosted rate limits and key management are only
approximated. Nothing here proves a hosted project is configured correctly (see the hosted checklist at the end).

Storage is deliberately **not** used (product images go to Cloudflare R2; see `storage-r2.md`, written by the backend work).

## Versions observed
Supabase CLI 2.67.1; GoTrue v2.184.0; PostgREST v14.1; Postgres 17.6.1.064 (`supabase/postgres`); Mailpit v1.22.3; Kong 2.8.1. Studio, realtime, edge runtime,
analytics, vector, imgproxy, storage and supavisor are excluded.

## Start / stop / reset
```bash
cd ana-depot-api/dev
# one-time: generate the ES256 JWT signing key (private; git-ignored) -> asymmetric tokens, verified by the backend through JWKS
supabase gen signing-key --algorithm ES256 --workdir . | head -1 | python3 -c "import sys,json; json.dump([json.loads(sys.stdin.readline())], open('supabase/signing_key.json','w'))"
chmod 600 supabase/signing_key.json
supabase start --workdir . -x realtime,imgproxy,studio,edge-runtime,logflare,vector,supavisor,postgres-meta,storage-api   # first run pulls images
supabase status --workdir .          # prints the local URLs and the PUBLISHABLE/anon key (public) and the secret key (server only, never in a browser)
supabase stop --workdir .            # stop (keeps data); add --no-backup to discard
supabase db reset --workdir .        # recreate the database
```
Only containers named `supabase_*_ana-depot` belong to this stack. Config: `dev/supabase/config.toml` (project id `ana-depot`): e-mail confirmations **on**, minimum password
length 8, asymmetric signing key, `site_url` = the web app on port 3100, redirect allow-list for `/{fr,ht}/auth/callback`, high local rate limits (tests create many users),
`jwt_expiry` 3600 (lower it, e.g. 30, to test expiry, then `supabase stop && supabase start`). Mailpit (mail catcher) UI/API: `http://127.0.0.1:54324` (`/api/v1/messages`).
`signing_key.json` must never be committed (`dev/.gitignore`).

URLs: API gateway `http://127.0.0.1:54321` (Auth `/auth/v1`, JWKS `/auth/v1/.well-known/jwks.json`, Data API `/rest/v1`); Postgres `127.0.0.1:54322` (user `postgres`, password `postgres` are
the well-known local-CLI dev values). Do not paste the local secret key anywhere that ships to a browser.

## Progress log (for resuming)
| Step | State |
|---|---|
| Stack configured and started (Auth, PostgREST, Postgres, Mailpit, Kong), ES256 signing | done; JWKS serves the ES256 key; tokens are `alg=ES256` with that `kid` |
| Storage disabled | done (`[storage] enabled=false`, container stopped) |
| Real token claims / sign-up behavior recorded | done (below) |
| Backend verified with real tokens (separate Postgres container) | done (below) |
| Backend on Supabase Postgres (dedicated schema + role), Flyway/validate, Data API exposure | in progress |
| Supabase UI block evaluation | done: not adopted as the forms (reasons below) |
| Privacy change (sign-up) in the web app | in progress |
| Env names (`NEXT_PUBLIC_API_BASE_URL`, publishable key) | pending |
| Real-stack browser suite `ana-depot-web/e2e/real-supabase` | pending |
| Web checks (tsc, lint, vitest, build, stub suites) | pending |

## Real behavior of Supabase Auth (GoTrue v2.184.0, confirmations ON), measured
**Access token (ES256).** Header `{alg: ES256, kid: <JWKS kid>, typ: JWT}`. Claims actually emitted:
`iss` = `http://127.0.0.1:54321/auth/v1` (no trailing slash), `sub` (UUID), `aud` = `authenticated`, `exp`, `iat`, `email`, `phone` (empty string), `app_metadata` `{provider, providers}`,
`user_metadata` `{email, email_verified, first_name, last_name, phone_verified, sub}` (user-editable; copies what sign-up sent), `role` = `authenticated`, `aal` = `aal1`,
`amr` `[{method: password, timestamp}]`, `session_id`, `is_anonymous` = false. **`email_verified` is NOT a top-level claim**; it is only inside `user_metadata`
(the backend `/me` already falls back to `user_metadata.email_verified`; it is informational only). Sign-in response: `access_token`, `refresh_token`, `expires_in` 3600, `expires_at`, `user`, `weak_password`.

**Sign-up with confirmations on.**
| Case | Response |
|---|---|
| new address | 200, user with 1 identity, `confirmation_sent_at`, 1 e-mail, ~450 ms |
| same address again, still unconfirmed | 200, same shape, a new e-mail is sent (rate limited by `max_frequency`: 429 `over_email_send_rate_limit` if repeated too fast) |
| same address, **confirmed** | **422 `user_already_exists` "User already registered"**, no e-mail, ~170-240 ms |
**The disclosure is NOT obfuscated by Supabase in this configuration.** Anyone with the public anon key can call `POST /auth/v1/signup` and learn whether a confirmed account exists.
Hiding it in our UI does not close that: it only stops our own screens from showing it (see the privacy section in `ana-depot-web/docs/authentication.md`).

**Other endpoints.** Sign-in before confirmation with the right password: 400 `email_not_confirmed`. Wrong password and unknown e-mail: both 400 `invalid_credentials` (same body), but timing differs
(median ~330 ms known user vs ~40 ms unknown). `recover` and `resend`: 200 `{}` for known and unknown addresses; a message is sent only for known ones; timing differs (~80-120 ms vs ~30 ms).
Weak password: 422 `weak_password`. Verification link: GoTrue `/auth/v1/verify?token=...&type=signup&redirect_to=...` answers 303 to the redirect target with the session in the URL fragment (implicit) or a `code` (PKCE).
Logout (`scope=local`) deletes the session: the refresh token is rejected (`refresh_token_not_found`) but **the already-issued access token stays valid until `exp`** (the backend verifies offline).
Editing own `user_metadata` (e.g. `is_admin: true`) is allowed by Auth and shows up in the next token: the backend ignores it (verified below).

## Backend verified against real Supabase tokens (Phase 2 backend jar, JWKS over HTTP)
Setup: `SUPABASE_JWT_ISSUER=http://127.0.0.1:54321/auth/v1`, default audience `authenticated`.
| Check | Result |
|---|---|
| Real customer token on `/api/v1/me` | 200, `staff:false`, `emailVerified:true` |
| No token / tampered payload / tampered signature / `alg=none` | 401 |
| Customer on `/api/v1/admin/x`; anonymous | 403; 401 |
| Public catalog anonymous | 200 |
| First admin bootstrap on a fresh database (`ANA_BOOTSTRAP_ADMIN_SUBJECT=<real user id>`), flyway 1..5 applied | staff `/me` `staff:true`; `/admin/x` 404 (authorized, no endpoint yet); `/actuator/metrics` 200 for staff, 403 for customer |
| Customer sets `user_metadata.is_admin/role/staff` via the real Auth API, re-logs in | token carries them; backend: `staff:false`, admin 403 |
| Wrong audience configured (`service_role`) | 401 |
| Wrong issuer configured | 401 |
| Issuer configured **with** trailing slash | **401** (real `iss` has none; the configured value must match exactly) |
| Issuer unset | 401 (fail closed), public catalog still 200 |
| After logout, same access token | still 200 until `exp` (documented offline-verification trade-off); refresh token revoked |
Not measured: expiry rejection of a real expired token and JWKS key rotation (see the section below once run).

## Supabase UI "password-based auth" block: evaluation (not adopted as the forms)
Registry `https://supabase.com/library/r/password-based-auth-nextjs.json` was read before installing anything. It adds source files (pages under `app/auth/*`, five form components,
`app/auth/confirm/route.ts`, `lib/supabase/{client,server,middleware}.ts`, `middleware.ts`) and shadcn `button card input label` (which in turn bring `cn`, clsx, tailwind-merge, class-variance-authority, radix slot/label and
shadcn's CSS variable theme). Findings against our requirements:
* `lib/supabase/middleware.ts` redirects **every unauthenticated request to `/auth/login`**: unusable for a public storefront; Next 16 uses `proxy.ts` (ours already refreshes the session with `getClaims()` without redirecting).
* Forms hardcode English literals, `/auth/*` and `/protected` paths with `next/link`/`next/navigation` (not locale-aware), and **render `error.message` straight from Supabase** (this would print "User already registered": the enumeration disclosure).
* No resend-verification, no validation/accessibility handling (aria wiring, focus on first invalid), no name fields; the confirm route's `next` check (`startsWith('/')`) accepts `//evil.example` (open redirect) and puts `error.message` in the redirect URL.
* Using it means a second visual language (shadcn theme) and ~5 new dependencies, or replacing its imports with our primitives and rewriting its strings/errors/redirects: what remains is the same `useState` + `supabase.auth.*` skeleton our forms already are.
Conclusion: the block cannot meet the requirements (locale routing, privacy, design system, security of the confirm route) without being rewritten line by line, so it was **not forced**. One valuable idea it contains is adopted as a **recommendation**, not
implemented: the server-side `token_hash` + `verifyOtp` confirmation route with custom e-mail templates, which works when the link is opened in a different browser or device (our current PKCE link needs the same browser).
