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
`jwt_expiry` 3600 (lower it, e.g. 20, to test expiry, then `supabase stop && supabase start && docker restart supabase_auth_ana-depot`). Mailpit (mail catcher) UI/API: `http://127.0.0.1:54324` (`/api/v1/messages`).
`signing_key.json` must never be committed (`dev/.gitignore`).

URLs: API gateway `http://127.0.0.1:54321` (Auth `/auth/v1`, JWKS `/auth/v1/.well-known/jwks.json`, Data API `/rest/v1`); Postgres `127.0.0.1:54322` (user `postgres`, password `postgres` are
the well-known local-CLI dev values). Do not paste the local secret key anywhere that ships to a browser.

## Progress log (for resuming)
| Step | State |
|---|---|
| Stack configured and started (Auth, PostgREST, Postgres, Mailpit, Kong), ES256 signing | done; JWKS serves the ES256 key; tokens are `alg=ES256` with that `kid` |
| Storage disabled | done (`[storage] enabled=false`, container stopped) |
| Real token claims / sign-up behavior recorded | done (below) |
| Backend verified with real tokens | done (below) |
| Backend on Supabase Postgres (dedicated schema + role), Flyway/validate | done (below) |
| Data API exposure test | done: **critical finding** if app tables live in `public` (below) |
| Short-expiry behavior | done (below) |
| Supabase UI block | adopted as source-copied components in `ana-depot-web` (see its `docs/authentication.md`) |
| Privacy change (uniform sign-up) | done in the web app; provider-level disclosure documented |
| Real-stack browser suite `ana-depot-web/e2e/real-supabase` (`npm run e2e:real`) | done: 59 checks passed against this stack + the Phase 2 API snapshot |

## Auth e-mail templates (bilingual, token_hash links)
`dev/supabase/templates/{confirmation,recovery}.html` are loaded through `[auth.email.template.*] content_path` (paths are relative to the **project root**, i.e. `./supabase/templates/...`). They read
`user_metadata.locale` (sent at sign-up) to pick French or Haitian Creole (Creole text needs native review) and link to `{{ .RedirectTo }}&token_hash={{ .TokenHash }}&type=signup|recovery`, so the link works in
any browser or device and for "resend". **Local-stack quirk:** GoTrue fetches the templates from Kong at startup and silently falls back to the default English templates if Kong is not ready yet
(`templatemailer_template_body_http_error ... connection refused` in `docker logs supabase_auth_ana-depot`). If the e-mails look like the default ones, run `docker restart supabase_auth_ana-depot`
after `supabase start`. Without the custom template the default link goes through GoTrue `/verify` and (for a resent mail) ends in an implicit-flow URL fragment that the app does not accept.

## Backend on Supabase Postgres (dedicated role and schema)
The backend never uses the `postgres` superuser-like role or the `public` schema. One-time setup (local values; the password is a dev-only example):
```bash
docker exec -i supabase_db_ana-depot psql -U postgres <<'SQL'
create role ana_depot_app login password 'change-me-dev-only';   -- no superuser/createdb/createrole
grant ana_depot_app to postgres;                                   -- needed: `postgres` is not a superuser on Supabase
create schema ana_depot authorization ana_depot_app;
SQL
```
Connection URL shape: `jdbc:postgresql://127.0.0.1:54322/postgres?currentSchema=ana_depot` with user `ana_depot_app` (`DB_URL`, `DB_USER`, `DB_PASSWORD`). `currentSchema` makes both Flyway and Hibernate (`ddl-auto=validate`) use that schema.
Result: Flyway V1-V5 applied, `validate` passed, the API started and answered `/actuator/health` UP. Hosted projects: use the direct or pooler connection string for a role created the same way.

### Data API (anon key) test: findings
PostgREST exposes only `public` and `graphql_public`.
* **Dedicated schema `ana_depot`: not reachable.** `GET /rest/v1/staff_members` with `Accept-Profile: ana_depot` and the anon key returns `PGRST106 Invalid schema: ana_depot`; with the default profile it returns 404 (no such relation in `public`).
* **CRITICAL if the tables are created in `public` as `postgres`** (tested, then cleaned up): Supabase's default privileges grant `anon`, `authenticated` and `service_role` access to tables created by `postgres` in `public`. With the public anon key
  anyone could read all rows **and write them**: an anonymous `INSERT` into `staff_members` returned **201**, which is a privilege-escalation path (the backend trusts that table for staff membership). Never create the application tables in `public`
  (or, if ever required, enable RLS with no policies and `revoke all ... from anon, authenticated`). On hosted projects also review "Exposed schemas" in the API settings and the default-privileges of the `postgres` role.
* The backend itself is unaffected by this: it connects as `ana_depot_app` over JDBC and verifies JWTs itself.

## Expiry and refresh (measured with `jwt_expiry = 20`, then restored to 3600)
* The backend rejected an expired real token with 401 only after about `exp + 60 s`: Spring's `JwtTimestampValidator` has a **default clock skew of 60 s**. Access tokens are verified offline, so after logout/revocation they stay valid until `exp` (+skew).
* A fresh token after re-login: 200. `refresh_token` grant returns a new access and refresh token; replaying the previous refresh token immediately after rotation answered HTTP 200 (GoTrue keeps a short reuse window), later replays are rejected.
* The web app refreshes the session in `proxy.ts` (`getClaims()`) and in the browser client; the real suite proves an expired access token with a valid refresh token still opens `/account` and the cookie is rotated.

## Limitations of the local stack
* **Key rotation is not testable here:** `[auth] signing_keys_path` accepts exactly ONE key in CLI 2.67.1 (a second key made `supabase start` fail with "multiple signing keys detected, only 1 signing key is supported"). JWKS rotation must be verified on a hosted project.
* SMTP delivery, hosted rate limits, CAPTCHA, leaked-password protection and dashboard settings are not exercised.

## Hosted project checklist (NOT done by anything here; hosted behavior is untested)
In the Supabase dashboard of the real project:
1. **URL Configuration:** Site URL = the production web origin; Redirect URLs allow-list `https://<domain>/fr/auth/callback` and `/ht/auth/callback` (add preview/staging origins deliberately).
2. **Authentication -> Sign In / Providers:** Email enabled, **Confirm email ON**, minimum password length 8+, enable leaked-password protection if the plan offers it; disable providers you do not use; decide about public sign-ups (see the privacy note in the web docs).
3. **Emails:** custom SMTP (the built-in sender is rate limited and for testing only); paste the two templates from `dev/supabase/templates/` (token_hash links) and set the subjects; have the Creole text reviewed.
4. **JWT signing keys:** use asymmetric signing keys (ES256/RS256) and rotate on a schedule; the backend must use the project JWKS (`SUPABASE_JWT_ISSUER=https://<ref>.supabase.co/auth/v1`, exact match, no trailing slash). Set JWT expiry (default 3600 s) deliberately and remember the +60 s skew and offline verification.
5. **Rate limits / CAPTCHA:** review the Auth rate limits (sign-ups, sign-ins, e-mails) and enable CAPTCHA (Turnstile/hCaptcha) if abuse appears.
6. **Data API:** keep application tables out of `public`; review exposed schemas; the app's data belongs to the backend database role, not to the Data API.
7. **Keys:** only the publishable/anon key goes into the web app (`NEXT_PUBLIC_*`, build-time); the secret/service-role key is never used by the web app or the backend and must not appear in any `NEXT_PUBLIC_*` variable.
8. First administrator: create the user, then insert its `sub` into `staff_members` through the documented backend procedure.

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

## Supabase UI "password-based auth" block
Adopted by `ana-depot-web` as source-copied components (not a hosted portal, not `@supabase/auth-ui-react`). The registry `https://supabase.com/library/r/password-based-auth-nextjs.json` was read first. What was kept and what was not copied
(its own Supabase clients and its `middleware`, which redirects every unauthenticated request to the login) is documented in `ana-depot-web/docs/authentication.md`.
