# Frontend ↔ backend integration map

Written at the start of Phase 3 from the **actual code** of both projects (not only their docs). It maps every frontend feature to the backend capability that must
serve it, so that nothing is built twice. Legend for the *Backend status* column:

* **REUSE** — a core use case already exists and is tested; only a controller/DTO is missing.
* **NEW EP** — a new endpoint over an existing use case/port is needed.
* **NEW UC** — a core use case / port / migration is missing.
* **DONE** — implemented (by a Phase 3 work package; see `docs/api.md` for the exact contract).
* **POLICY** — blocked by an undecided business decision; deliberately not implemented, no default invented.

The frontend today (`ana-depot-web`): real Supabase Auth (cookie session, `GET /api/v1/me` gate) + everything else on browser-local demo stores
(`src/lib/stores/*`, `src/lib/api/{catalog-admin,reservations}.ts` — async, `Result<T>` shaped; they are the seam where HTTP replaces the demo bodies).
`src/lib/api/http.ts` is a typed client (bearer token, RFC 9457 errors, `Idempotency-Key`, `X-Correlation-Id`) that nothing calls yet.

## 1. Public storefront

| Frontend feature (route / module) | Needs from the backend | Backend status |
|---|---|---|
| Home (`features/home/sections.tsx`): categories, featured products, new arrivals | `GET /categories`, `GET /products` (sort newest, page size) | **DONE** (WP2) — "featured rank" / "isNew" have no backend field; use sort `newest` (documented gap) |
| Catalog `/products`, filters, pagination (`features/catalog/*`, `lib/catalog-query.ts`) | search `q`, filters (category, currency, wholesale, in-stock, `attr=key:option`, brand, price range **within a currency**), sorts, paging, facets | **DONE** (WP2). Facets are derived by the client from the category's filterable attributes (`GET /categories/{slug}`) |
| Search box suggestions (`features/search`) and the search page | `GET /products/search?q=&size=` (PostgreSQL FTS: accent-insensitive, prefix, AND, ranked, French + Haitian Creole; rate limit `catalog.search`; `/products?q=` is the same use case) | **DONE** (WP2, docs/search.md) |
| Product page `/products/[slug]` (`features/product/*`) | `GET /products/{idOrSlug}`: per-currency prices + tiers, variants (+ own prices), images, attributes, availability | **DONE** (WP2) |
| Cart pricing (`lib/cart.ts`: tier re-pricing by quantity, stock cap, min order) | The cart is client state, but the **server is authoritative**: prices/tier/availability come from the product resource; the final price is recomputed by `POST /reservations`. No cart endpoint (no server cart) | **DONE** (no new EP needed); client mirrors `ProductPrice.quote` for display only |
| Currency choice per cart line | each line names a currency the product has; missing currency price is `price_not_available` | **DONE** (existing `Product.quote`) |
| Info pages `/info/pickup`, `/info/contact` (`features/info`) | `GET /store/pickup` (public, only if enabled + configured) | **DONE** (WP1) |
| Info pages `returns/privacy/terms` | legal content | **POLICY** (placeholders stay placeholders) |
| Wholesale price visibility rules | who may see/use wholesale prices | **POLICY** — currently public for everyone (as in the storefront) |

## 2. Guest reservation & tracking

| Frontend | Backend | Status |
|---|---|---|
| Reserve flow `/reserve` (`reserve-flow.tsx`, `lib/api/reservations.submitReservation`) | `POST /reservations` — server re-prices (`Product.quote`), checks pickup availability, stock under row locks, idempotency key required, rate limit `reservation.submit`; returns the customer view | **DONE** (WP3) over **REUSE** `ReservationService.submit` |
| Pickup availability gate (`pickupAvailability`) | refuse with `pickup_not_configured` / `pickup_disabled`; snapshot current pickup settings | **DONE** (WP1) — **NEW UC** (`PickupSettings`) |
| Confirmation `/reserve/confirmation/[reference]` | the `POST /reservations` response | **DONE** |
| Tracking lookup `/reservations`, `/reservations/[reference]` (`lookupReservation`, `access.ts: phoneMatches`) | `POST /reservations/lookup {reference, phone}` — identical failure for unknown reference and wrong phone; phone matched on digits with/without dial code | **DONE** (WP3) |
| Customer view type (`customer-view.ts`) | a separate DTO that cannot carry internal notes/staff identity/actor ids | **DONE** (WP3) |
| Customer cancels own reservation (`customerCanCancel`, `canCancel`) | cancellation after READY is undecided; the frontend rule ("until paid") is its own invention | **POLICY** — no customer cancel endpoint; the view returns `canCancel:false` |
| Guest "verified references" kept in browser (`guest-refs-store`) | stays client-side (references the browser already verified); the server never trusts it | no backend need |

## 3. Customer accounts

| Frontend | Backend | Status |
|---|---|---|
| Auth (register/sign-in/verify/reset) | Supabase Auth directly (browser ↔ Supabase); backend only verifies tokens | existing (`SupabaseJwtDecoders`) |
| Who am I / admin gate (`lib/auth/*`, `GET /api/v1/me`) | `/me` {userId,email,emailVerified,staff,permissions,customerProfileId} | existing; `customerProfileId` now resolves to the user's own (non-guest) customer record (WP4) |
| `/account/profile` (`account-views.tsx`, `account-store`) | `GET/PUT /me/profile` (names, dial code, phone, email) | **DONE** (WP4) — **NEW UC** |
| `/account/reservations` | `GET /me/reservations`, `GET /me/reservations/{id}` (only the user's own) | **DONE** (WP4) |
| Linking reservations to an account | a bearer token on `POST /reservations` links to the user's customer record; **guests are never auto-merged** into an account (even with the same phone) | **DONE** (WP4); verified claim process **POLICY/future** |
| Registered vs guest customers | `customers.auth_user_id` NULL ⇒ guest | **DONE** (V7); exposed in admin views later |

## 4. Administration (next backend fork; contract already designed in `docs/api.md`)

| Frontend screen | Backend capability | Status |
|---|---|---|
| Dashboard (`dashboard.tsx`): counts, low/out of stock, recent activity, per-currency money | admin read models: reservation counts by status, stock states, recent audit/movements, per-currency paid/outstanding | **NEW EP** (queries over existing tables; no new rules) |
| Products list/create/edit/activate (`products/*`, `catalog-admin.saveProduct/setProductStatus`) | `CatalogService.saveProduct` (validates category, SKU, attributes, publishability, audits price changes) | **REUSE** use case; **NEW EP** (admin DTOs); product form already models per-currency prices |
| Product images (`product-form-parts.tsx` uses data URLs today) | `UploadService` request/complete + a real `ObjectStorage` adapter | **REUSE** use case; **NEW EP** + **NEW UC** (adapter) — provider decision pending (docs/adr/0007) |
| Variants | inside `ProductRequest` (axis values = category CHOICE variant axes) | **REUSE**; gap: ad-hoc per-product option axes are not modelled |
| Categories + attributes (`categories/*`) | `CatalogService.saveCategory`; `category_attribute_*` | **REUSE** use case; **NEW EP** |
| Category "in use" check on deactivate (`category_in_use`) | count of products per category | **NEW UC** (small query) |
| Inventory (`inventory.tsx`, `catalog-admin.adjustStock`) | `InventoryService.adjust` (idempotent, audited), movements | **REUSE**; **NEW EP**; listing endpoint = **NEW UC** (read model join product/variant/stock) |
| Reservation list/filter/search (`reservation-list.tsx`, `lib/reservations/query.ts`) | filtered/paged query (status, date range, text on reference/name/phone/email, sort newest/oldest) | **NEW UC** (`ReservationRepository` query port) |
| Reservation detail + actions (`reservation-detail.tsx`, `reservation-actions.tsx`) | accept/reject/ready/collect/cancel/expire = `ReservationService`; notes = `Reservation.addNote` (needs a use case); history = existing | **REUSE** + **NEW UC** (notes), **NEW EP** |
| "Change pickup for one reservation" (`changePickup` action) | per-reservation pickup override | **NEW UC**, low priority — snapshot override not modelled in core |
| Payments page (`payments.tsx`) | `PaymentService.record/reverse/adjust/reconcile`; list payments by currency/reservation | **REUSE** + **NEW UC** (payment listing query) |
| Payment dialog (currency choice, per-currency due) | `Reservation.balances()` in the reservation response; idempotency key REQUIRED | **REUSE** |
| Customers list/detail (`customers.tsx`) | search customers + their reservations; registered vs guest flag | **NEW UC** (query), **NEW EP** |
| Reports (`reports.tsx`) | per-currency: reservation value (by status), payments received (ledger sum), outstanding balances; never mixes currencies; uncollected never "revenue" | **NEW UC** (aggregate queries) |
| Store settings (`store-settings.tsx`, `pickup-settings-form.tsx`) | pickup settings (WP1); everything else (identity, hold policy beyond `holdPeriodDays`, operational preferences) has **no** real model | pickup **DONE**; rest **POLICY** |
| Audit history screens | `audit_events` by target | **NEW EP** (read-only) |
| Staff gate | `staff_members`; `/api/v1/admin/**` requires `STAFF` (deny by default) | existing |
| Hold period / expiry job | `holdPeriodDays` is stored (WP1) but **no expiry job** and no default — undecided | **POLICY** (`expires_at` is set only if the setting is non-null at READY time; no scheduler expires reservations) |

## 5. Cross-cutting

| Concern | Where | Status |
|---|---|---|
| Idempotency keys on writes | `IdempotencyService`; header `Idempotency-Key` (required: reservation submit, payments, reversals, adjustments, stock adjustments) | existing infra; wired per endpoint |
| Rate limits | `RateLimitFilter` rules → `ana.rate-limit.limits` (catalog.read, catalog.search, reservation.submit, reservation.lookup, upload.request, admin.write) | existing; rules extended (this phase) |
| Errors | problem+json with stable `code` (`GlobalExceptionHandler`); frontend `ApiError` expects `status/code/title/detail/errors/correlationId` and `Retry-After` | existing; `errors[]` shape documented in `docs/api.md` |
| Correlation id | `X-Correlation-Id` echoed + MDC + audit/outbox | existing |
| Money | decimal strings + `currency`; frontend `Money` = integer minor units → conversion at the client boundary only (`majorToMinor`) | contract documented |
| Localized text | `{lang: text}` maps (`LocalizedText`) ⇔ frontend `LText {fr, ht}` | compatible |

## 6. Gaps / differences between docs and code found during inspection
1. `docs/api.md` (Phase 1) designed `GET /reservations/lookup?reference&phone`; phone numbers must not travel in URLs, so the implemented contract is **`POST /reservations/lookup`** (rate-limit rule changed accordingly).
2. `docs/api.md` marked all reservation/payment/admin endpoints "designed"; at the start of Phase 3 **no** controller existed beyond products and `/me`. `docs/api.md` now states exactly what is implemented.
3. The reservation use case took an opaque pickup snapshot string from its caller. Pickup settings had **no** backend model; they now do (V6) and the snapshot is taken from them.
4. `customers` had `unique(dial_code, phone)` for everyone, which would force a registered account and a guest with the same phone into one row (an implicit merge). V7 restricts that uniqueness to guest rows.
5. The frontend still models a per-product `pricing.currency`; the backend is per-product **multi-currency** (one product, many prices). The Phase 2 frontend fork has migrated its product form; the cart/reservation DTOs must carry `currency` per line.
6. The storefront has `featuredRank`, `isNew`, `unitNote` (localized) on products; the backend has `unit_note` but no featured rank / "new" flag (use sort by creation date).
7. `docs/domain.md` says variant axes are category attributes; the frontend variant editor can still define product-specific option names. Known gap, unchanged.
8. The frontend `customerCanCancel` rule and the customer "cancel" button have no backend counterpart (policy undecided).
9. Frontend category `moveUp/moveDown` ordering ⇒ `display_order` exists; a reorder endpoint is a trivial admin write.

## 7. Progress notes (for resuming; update as work packages finish)
Migration numbering used: V6 store_settings, V7 catalog_search, V8 catalog_fulltext_search, V9 customer accounts (WP4), V10 Data-API lockdown (must stay LAST so it covers every table).
| WP | State |
|---|---|
| 0 integration map | done (this file) |
| 1 pickup settings | done + tested (`PickupSettingsApiIntegrationTest`, 8 tests): V6, core settings use case, `GET /store/pickup`, `GET/PUT /admin/settings/pickup`, reservation gate + snapshot |
| 2 public catalog | DONE + tested: `GET /categories`, `/categories/{slug}`, `/products` (browse/filter), `/products/search` (same use case), `/products/{idOrSlug}`; availability from shared stock; search = PostgreSQL full-text (V8, ADR 0015, docs/search.md); `POST /admin/catalog/search/reindex` |
| 3 guest reservations | not started |
| 4 customer accounts | not started |
| 5-7 tests/docs | partially (docs/api.md still describes Phase 1) |
| Addendum A Supabase Postgres config + V9 lockdown | not started |
| Addendum B Cloudflare R2 adapter + image endpoints | not started |
| Addendum C docs correction (Supabase Storage -> R2) | not started |
