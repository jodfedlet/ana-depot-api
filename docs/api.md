# API design

Base path `/api/v1`. JSON. **Implemented today:** the endpoints in [Implemented](#implemented). Everything under [Designed](#designed-resources) is a contract proposal whose
**use cases already exist and are tested in `core`**; only the controllers/DTOs are missing. DTOs are always separate from JPA entities and from domain objects.

## Conventions
* Ids are UUIDs. Timestamps ISO-8601 UTC. Localized text is an object `{ "fr": "...", "ht": "..." }` (open set of languages).
* **Money is a decimal string** (`"25000.00"`) next to an explicit `currency` (`"HTG"|"USD"`). Never a JSON number. The currency is **never inferred** from the user's location and amounts are never converted.
* **Prices are decided by the server.** A client names `{productId, variantId?, quantity, currency}`; the server looks up the active price of that product in that currency and applies the right tier.
* A reservation response carries **`totals`: one entry per currency** (never a single total).
* Authentication: `Authorization: Bearer <Supabase access token>` ([auth.md](auth.md)). Public: health, read-only catalog, (future) guest reservation submit and tracking. Everything else: authenticated; `/api/v1/admin/**`: staff. Unlisted paths are denied.

## Errors (RFC 9457 `application/problem+json`)
```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "Only 3 available, 5 requested",
  "code": "insufficient_stock", "correlationId": "5e9d2a8c-…" }
```
`code` is stable and machine-readable (clients translate it; `detail` is for developers). `correlationId` echoes the `X-Correlation-Id` request header when well-formed (8–64 of `[A-Za-z0-9_-]`) or a generated one.
| Status | When | Examples of `code` |
|---|---|---|
| 400 | malformed request / bad parameter / validation | `validation_failed` (+ `errors[{field,message}]`), `bad_request` |
| 401 | missing/invalid/expired token (generic, never says why) | `unauthenticated` |
| 403 | authenticated but not allowed | `forbidden` |
| 404 | resource missing (also used to hide inactive/draft products and other people's uploads) | `not_found` |
| 409 | state conflict | `insufficient_stock`, `invalid_status`, `already_paid`, `already_reversed`, `duplicate_sku`, `payment_required`, `idempotency_key_reuse`, `concurrent_update` |
| 422 | business rule violated | `price_not_available` (product not sold in that currency), `below_minimum_order`, `currency_mismatch`, `currency_invalid`, `product_unavailable`, `reason_required`, `tier_*`, `idempotency_key_required`, `invalid_page` … |
| 429 | rate limit (`Retry-After` seconds) | `rate_limited` |
| 500 | unexpected (details never leaked) | `internal_error` |

## Pagination
`?page=0&size=20` (0-based, `size` ≤ 100; larger → 422 `invalid_page`). Response: `{ items, page, size, totalItems, totalPages }`, stable order (`createdAt desc, id`).
Offset paging is adequate at Ana Dépôt's catalog size; switch to keyset (`after=<cursor>`) for admin lists that grow (reservations, ledger) — a compatible addition.

## Idempotency
`Idempotency-Key: <8–128 chars of A-Z a-z 0-9 _ - : .>`.
* **Required** (server returns 422 `idempotency_key_required` otherwise): record payment, reverse payment, ledger adjustment, stock adjustment.
* **Recommended**: reservation submit, reservation transitions, upload request.
* Scope: (actor, operation, key). Same key + same payload → the **original result**, no second effect; same key + different payload → 409 `idempotency_key_reuse`. Keys survive restarts/instances; retention `ana.idempotency.retention` (default 7 days), then forgotten.
* A failed request does not consume the key (its transaction rolled back with it).
Guests have no identity: their scope is `anonymous` and the payload fingerprint includes the contact details, so one guest cannot replay another's request.

## Concurrency
* Stock: `SELECT … FOR UPDATE` on the inventory rows in a stable order inside the reservation transaction (ADR 0009). Losers get 409 `insufficient_stock`.
* Reservation transitions and payments lock the reservation row; the loser of a race sees the new state (409 `invalid_status` / `already_paid`).
* Admin edits of products/categories use optimistic `version`; a stale update → 409 `concurrent_update`. (Planned HTTP form: `ETag`/`If-Match` carrying the version.)

## Implemented
| Method & path | Auth | Response |
|---|---|---|
| `GET /api/v1/products?page&size` | public | `Page<ProductResponse>` — **ACTIVE products only** |
| `GET /api/v1/products/{id}` | public | `ProductResponse`; draft/inactive/unknown → 404 |
| `GET /api/v1/me` | any valid token | `MeResponse` |
| `GET /actuator/health` | public | liveness/readiness |
| `GET /actuator/metrics/**` | staff | Micrometer metrics |

`ProductResponse` — **one product, several currency prices**:
```json
{ "id": "…", "sku": "MAT-001", "slug": "…", "categoryId": "…",
  "name": { "fr": "Matelas", "ht": "Matla" }, "description": {}, "brand": null, "sellingUnit": "UNIT", "unitNote": {},
  "prices": [
    { "currency": "HTG", "retailPrice": "25000.00", "salePrice": null, "minOrderQuantity": 1, "wholesaleEnabled": true,
      "wholesaleTiers": [ { "minQuantity": 5, "unitPrice": "23000.00" } ] },
    { "currency": "USD", "retailPrice": "180.00", "salePrice": null, "minOrderQuantity": 1, "wholesaleEnabled": true,
      "wholesaleTiers": [ { "minQuantity": 5, "unitPrice": "165.00" } ] } ],
  "variants": [ { "id": "…", "sku": "…", "axisValues": { "<attributeId>": "<optionId>" }, "retailPrices": [ { "currency": "HTG", "retailPrice": "30000.00" } ] } ],
  "images": [ { "id": "…", "url": "https://…", "alt": {}, "position": 0 } ],
  "attributes": [ { "attributeId": "…", "optionId": "…", "text": null, "number": null } ] }
```
Only active price configurations and variants are exposed; stock quantities are not part of this resource.
`MeResponse`: `{ userId, email, emailVerified, staff, permissions: [], customerProfileId }` (`permissions` is empty by design; `emailVerified` is informational and never used for authorization).

## Designed resources
(`A` = admin/staff, `G` = guest allowed, `C` = authenticated customer.) All write endpoints return the created/updated resource; all admin endpoints are under `/api/v1/admin`.
| Resource | Endpoints | Request DTO → notes |
|---|---|---|
| Categories | `GET /categories` (public, active), `POST/PUT /admin/categories`, `PATCH /admin/categories/{id}/active` | `CategoryRequest{slug,name,description,imageUrl,active,displayOrder}` |
| Category attributes | `PUT /admin/categories/{id}/attributes` (replace set) | `AttributeRequest{key,label,type,unit,required,filterable,variantAxis,displayOrder,active,options[{code,label,order,active}]}` — only CHOICE may filter/define variants |
| Products | `POST /admin/products`, `PUT /admin/products/{id}`, `PATCH /admin/products/{id}/status`, `GET /admin/products` (all statuses) | `ProductRequest{sku,slug,categoryId,name,description,brand,sellingUnit,unitNote,attributes[],variants[]}`; publishing re-validates |
| Product prices | `PUT /admin/products/{id}/prices/{currency}`, `DELETE …/{currency}` | `PriceRequest{retailPrice,salePrice,minOrderQuantity,active,wholesaleTiers[{minQuantity,unitPrice}]}` — **one currency at a time, independent**; every amount in `{currency}`; audited (previous/new) |
| Variants | `PUT /admin/products/{id}/variants` | `VariantRequest{sku,active,position,axisValues,retailPrices[{currency,retailPrice}]}` |
| Images | `POST /admin/products/{id}/images/uploads` → ticket; `POST /admin/uploads/{id}/complete` | `UploadRequest{contentType,size}` → `{uploadId,url,method,headers,expiresAt}` (ADR 0007) |
| Inventory | `GET /admin/inventory`, `GET /admin/inventory/{itemId}/movements`, `POST /admin/inventory/{itemId}/adjustments` | `AdjustmentRequest{type: ADD/REDUCE/RECOUNT, quantity, reason, note}` + **Idempotency-Key required**; response shows onHand/reserved/available |
| Reservations (guest) | `POST /reservations` (G), `GET /reservations/lookup?reference=…&phone=…` (G, rate-limited) | `SubmitRequest{lines[{productId,variantId?,quantity,currency}],contact{…},locale,acknowledgedTerms}`; lookup answers identically for "unknown reference" and "wrong phone" (no enumeration) and never returns internal notes |
| Reservations (admin) | `GET /admin/reservations`, `GET /admin/reservations/{id}`, `POST …/{id}/accept\|reject\|ready\|collect\|cancel\|expire`, `POST …/{id}/notes` | `RejectRequest{reason,note}`, `CancelRequest{reason}`, `ReadyRequest{expiresAt?}` |
| Reservation payments | `POST /admin/reservations/{id}/payments`, `POST /admin/payments/{id}/reversals`, `POST /admin/reservations/{id}/ledger-adjustments`, `GET /admin/reservations/{id}/ledger`, `GET …/reconciliation` | `PaymentRequest{amount,currency,method,receivedAt,reference,note}` — **Idempotency-Key required** |
| Customers | `GET /admin/customers?query=`, `GET /admin/customers/{id}` (A) ; customer's own profile via `/me` | contact + reservation history; privacy: staff only |

Reservation response (designed): `{ id, reference, status, paymentStatus, lines[{productName, variantLabel, unit, quantity, currency, unitPrice, pricingKind, tierMinQuantity, lineTotal}],
totals[{currency,total,paid,due}], pickup{…snapshot}, history[…], createdAt }`.

## Rate limits (configurable, per operation; key = user id when authenticated, else client IP)
`catalog.read 300/min`, `catalog.search 60/min`, `reservation.submit 10/10 min`, `reservation.lookup 20/10 min`, `upload.request 30/min`, `webhook.receive 300/min`, default 120/min
(`ana.rate-limit.limits`). The filter already maps these operations to their future paths. `X-Forwarded-For` is honoured **only** from configured trusted proxies (ADR 0008).
