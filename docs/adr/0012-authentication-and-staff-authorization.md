# ADR 0012 — Supabase Auth identity, server-side staff authorization
Status: accepted (implemented: JWT validation, `staff_members`, `/me`, bootstrap; see docs/auth.md).

**Decision.** Supabase Auth proves identity; **Ana Dépôt decides permissions**. The backend validates Supabase access tokens (signature via JWKS, issuer, audience, expiry, UUID subject; fail closed when unconfigured), then maps `sub` to authorities using **its own table** `staff_members` — never token claims or user-editable metadata.
Deny by default; guests never need an account; staff status needs an explicit, auditable provisioning step (documented bootstrap by user id; no public staff sign-up, no hardcoded credentials, no automatic promotion). Roles/permissions are **not modelled** (undecided): membership is all-or-nothing and `/me.permissions` is empty, leaving a clean place to add roles later without touching `core`.
**Trade-offs.** Tokens are verified offline, so a revoked session works until its access token expires (staff removal, however, is immediate because we look membership up per request). Legacy HS256-signed Supabase projects are not supported (a shared symmetric secret in the backend is a larger blast radius).
**Honesty:** automated tests use locally generated keys, not a real Supabase project.
