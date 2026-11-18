# Authentication and authorization boundary

**Supabase Auth is the identity provider** (confirmed). The backend verifies Supabase access tokens; it never sees passwords, never creates sessions and never holds the
Supabase service-role key. Supabase-specific code lives only in `infra.security` (`SupabaseJwtDecoders`); `core` knows nothing about Supabase — it receives an `Actor(kind, userId)`.

## Four different things (do not conflate them)
| Concept | Where it lives | Meaning |
|---|---|---|
| **Supabase user identity** | Supabase `auth.users`; the token `sub` (UUID) | *Who* is calling. Proven by a signed token. |
| **Ana Dépôt customer profile** | `customers` (`auth_user_id` links it to the identity) | Contact details and reservation history. Optional: guests have no identity and no account. |
| **Staff membership** | `staff_members(user_id, active, …)` | *Whether* this identity works for the store. Only this table makes someone staff. |
| **Store permissions** | **not modelled** | Roles/permissions are an open business decision. Membership is all-or-nothing today; `permissions` in `/me` is an empty list. |

Authentication ≠ authorization: a valid token says who you are, not what you may do. **Not every authenticated user is staff.**

## Token validation (every request with a bearer token)
`SupabaseJwtDecoders.fromIssuer(issuer, audience)` — Spring Security resource server + Nimbus:
* signature verified against the project's published keys at `<issuer>/.well-known/jwks.json` (algorithms **ES256, RS256** only; `none`/HMAC refused);
* `iss` must equal `ana.security.jwt-issuer` (`https://<project-ref>.supabase.co/auth/v1`); `aud` must contain `ana.security.jwt-audience` (default `authenticated`);
* `exp`/`nbf` checked (default 60 s skew); `sub` must be a UUID;
* any failure → **401** with a generic problem body (the reason is never disclosed). With **no issuer configured the decoder rejects every token** (fail closed); public catalog reads still work.
Then `StaffAuthoritiesConverter` builds the authentication: everyone valid gets `AUTHENTICATED`; `STAFF` is added **only if an active `staff_members` row exists for `sub`**
(a primary-key lookup per request). `role`, `app_metadata`, `user_metadata`, `roles`, `is_admin`… in the token are **ignored** (user-editable metadata can never grant access — tested).

## Access rules (`SecurityConfig`, deny by default)
| Path | Rule |
|---|---|
| `/actuator/health/**`, `GET /api/v1/products/**`, `GET /api/v1/categories/**` | public (browsing needs no account) |
| `/api/v1/me` | authenticated |
| `/api/v1/admin/**`, `/actuator/metrics/**` | `STAFF` (403 for a customer, 401 without token) |
| anything else | denied (401 anonymous / 403 authenticated) |
Guest reservation submit/tracking will be added as **public** endpoints with their own rate limits (guest reservations stay possible); they are never gated on authentication.
Sessions: stateless, no cookies on the API, CSRF disabled for that reason.

## What the backend needs from an authenticated request
`Authorization: Bearer <access_token>`; from the verified token: `sub` (user id), `email` (informational). Recorded wherever a person acts: payments, ledger entries, stock
movements, status history, audit events (`recorded_by`/`actor_user_id` = `sub`), plus the correlation id.

## `GET /api/v1/me` (what the frontend uses to decide what to show)
`200 { userId, email, emailVerified, staff, mfaRequired, permissions: [], customerProfileId }` / `401`. The frontend must treat this as **display guidance only**: every protected endpoint
re-checks on the server. `emailVerified` is informational (Supabase only issues sessions to confirmed addresses when confirmation is enabled in the project); it never grants anything.

## Provisioning the first administrator (no hardcoded credentials, no public staff sign-up)
1. The future administrator registers normally through the storefront (customer sign-up) and confirms their e-mail. This creates **no** privileges.
2. Read their **user id** (UUID) in the Supabase dashboard (Authentication → Users).
3. Deploy once with `ANA_BOOTSTRAP_ADMIN_SUBJECT=<that UUID>`. On startup `StaffBootstrap` inserts the row **only if `staff_members` is empty**, and writes an audit event.
   The variable is ignored once any staff exists (a stale value cannot re-grant) and can be removed afterwards.
4. Further staff: a team member opens **Administration > Équipe**, creates an **invitation code** (shown once, stored only as a SHA-256 hash, valid `ana.staff.invitation-days`, default 7) and gives it to the colleague, who creates an account (or signs in) and redeems it at `/{locale}/account/join-team` (`POST /api/v1/me/staff-invitation`). The grant goes to the redeemer's own verified token subject: no e-mail address typed by anyone is trusted, and the backend never needs the identity provider's service-role key.
   Members can be deactivated and reactivated (`PUT /api/v1/admin/staff/{userId}`; effective on the next request). Nobody can change their own access and the last active member cannot be removed. Every step is audited. All members have the same rights (roles: decision D14).
No default admin account exists anywhere, and there is no way to become staff without a code from a current member (or the one-time bootstrap above).

## Staff second factor (MFA)
`ana.staff.require-mfa` (env `ANA_STAFF_REQUIRE_MFA`, default **false**; recommended **true** in production, decision D15). When on, an active staff member whose access token does not carry the signed claim `aal=aal2` (Supabase sets it after a TOTP code was verified) is **not** staff for that session: `/me` answers `staff:false, mfaRequired:true` and `/admin/**` answers `403 mfa_required`. The web then shows only the TOTP enrol/verify step (Supabase Auth MFA, browser side with the public key). The claim is part of the signed token, so `user_metadata`/`app_metadata` can never fake it (tested). Customers are never asked. The hosted Supabase project must have TOTP enabled (Authentication > Multi-factor); the local stack has it on (`dev/supabase/config.toml`).
Verified: `StaffMfaApiIntegrationTest` (claim handling with locally signed tokens) and the real-stack browser suite `npm run e2e:team` in `ana-depot-web` (real GoTrue enrolment, real `aal2` tokens). NOT verified: a hosted Supabase project.

## Session behavior and revocation
Access tokens are verified **offline** (no call to Supabase per request). A revoked/signed-out session therefore stops working when its access token expires (Supabase default lifetime
is short, typically 1 hour; keep it short). Staff removal is **immediate** because membership is read from our table each request. If instant token revocation is ever required, add a token
deny-list keyed by `jti`/session id — not built.

## Environment
| Variable | Where | Notes |
|---|---|---|
| `SUPABASE_URL` | backend | project URL; when `SUPABASE_JWT_ISSUER` is empty the issuer is `<SUPABASE_URL>/auth/v1` |
| `SUPABASE_JWT_ISSUER` | backend | `https://<project-ref>.supabase.co/auth/v1` (wins over `SUPABASE_URL`) |
| `SUPABASE_JWKS_URL` | backend, optional | where the signing keys are fetched from; default `<issuer>/.well-known/jwks.json`. The `iss` claim is still checked against the issuer, whatever the key URL (tested) |
| `SUPABASE_JWT_AUDIENCE` | backend | default `authenticated` |
| `ANA_BOOTSTRAP_ADMIN_SUBJECT` | backend, one-time | user id of the first admin |
| `ANA_STAFF_REQUIRE_MFA` | backend | `true` = staff need a session with a verified second factor (default `false`; D15) |
| `ANA_STAFF_INVITATION_DAYS` | backend | validity of a team invitation code (default 7) |
| `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY` | frontend (public) | the anon key is public by design |
| `API_BASE_URL` | frontend server only | the frontend's server calls `/me` and the API with the user's access token |
The **service-role key is not used by the backend and must never reach a browser**; if a server-side admin operation ever needs it, it is a server-only secret in the deployment secret store.
Legacy Supabase projects that sign tokens with a shared HS256 secret are **not supported** (they would put a symmetric secret in the backend); use asymmetric signing keys.
Never commit real values: `.env.example` has placeholders only.

## Local development
Run the backend with `SUPABASE_URL` (or `SUPABASE_JWT_ISSUER`) of a Supabase project (a free project is enough), or of the local stack ([local-supabase.md](local-supabase.md); then `SUPABASE_JWKS_URL` may point to the stack's JWKS), or leave both empty to work only with public endpoints.
The database connection (Supabase PostgreSQL) is documented in [supabase-database.md](supabase-database.md).

## Testing honesty
* `SecurityIntegrationTest` exercises the **real filter chain and real validation rules** with tokens signed by a **locally generated RSA key** (`SupabaseJwtDecoders.fromPublicKey`).
  It proves: missing/garbage/expired/wrong-issuer/wrong-audience/forged-signature/`alg=none`/non-UUID-subject tokens are rejected; customers are not staff; staff comes only from the table; metadata claims grant nothing; deactivation is immediate; deny-by-default; the fail-closed decoder.
* **No test talks to a real Supabase project**, so these are *not* Supabase integration tests: JWKS fetching/caching and Supabase's real token shape are verified only by code review and by running against a real project (the first thing to do when a project exists).
* `StaffBootstrapTest` (mocked collaborators) covers the provisioning rules.
