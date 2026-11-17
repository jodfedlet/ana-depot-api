# API reference (what is implemented)

Base path `/api/v1`, JSON (`application/json`). **Everything on this page is implemented and covered by tests.** Anything not listed does not exist (unlisted paths answer 401/403: deny by default).
DTOs are separate from JPA entities and from domain objects. Search specifics: [search.md](search.md). Storage specifics: [storage-r2.md](storage-r2.md). Which web screen uses which call: [integration-map.md](integration-map.md).

## Conventions
* Ids are UUIDs. Timestamps are ISO-8601 UTC (`2026-11-16T18:30:00Z`). Localized text is an object `{ "fr": "...", "ht": "..." }`.
* **Money is a decimal string** (`"25000.00"`) next to an explicit `currency` (`"HTG"` or `"USD"`). Never a number. Currencies are never converted or inferred; there is **no combined total**: reservations carry one total per currency.
* **Prices are decided by the server.** A client names `{productId, variantId?, quantity, currency}`; any price, total or other unknown JSON property in a request is ignored.
* Enum values are upper case (`PENDING_REVIEW`, `IN_STOCK`, ...), except the public pickup `status` (`available | disabled | not_configured`).
* Authentication: `Authorization: Bearer <Supabase access token>` ([auth.md](auth.md)). Authorization is decided on the server: `/api/v1/admin/**` needs a **staff** row in `staff_members`; `/api/v1/me/**` any valid token; the rest of the table below is public.
  **A bearer token that is present but invalid or expired is a 401 even on a public endpoint** (it is never silently treated as a guest): send the header only when the user is signed in and the token is fresh.
* **Browsers** may call the API directly only from the origins listed in `ANA_CORS_ALLOWED_ORIGINS` (comma-separated exact origins, no wildcard, no cookies; default: none). Exposed response headers: `X-Correlation-Id`, `Retry-After`; accepted request headers: `Authorization`, `Content-Type`, `Accept`, `Idempotency-Key`, `X-Correlation-Id`.
* Every response carries `X-Correlation-Id` (echo of a well-formed request header, else generated).

### Endpoints
| Method and path | Who | Rate-limit operation | What |
|---|---|---|---|
| `GET /actuator/health` | public | - | liveness/readiness |
| `GET /actuator/metrics/**` | staff | - | Micrometer metrics |
| `GET /api/v1/categories` | public | `catalog.read` | active categories with product counts |
| `GET /api/v1/categories/{slug}` | public | `catalog.read` | one category with its attributes and options |
| `GET /api/v1/products` | public | `catalog.read` (`catalog.search` when `q` is set) | browse/filter/search, paged |
| `GET /api/v1/products/search` | public | `catalog.search` | same use case; always the search operation |
| `GET /api/v1/products/{idOrSlug}` | public | `catalog.read` | one active product |
| `GET /api/v1/store/pickup` | public | none | pickup availability and details |
| `POST /api/v1/reservations` | public (token optional) | `reservation.submit` | create a reservation (**Idempotency-Key required**) |
| `POST /api/v1/reservations/lookup` | public | `reservation.lookup` + `reservation.lookup.reference` | find a reservation by reference + phone |
| `GET /api/v1/me` | any token | `account.read` | who am I (identity, `staff`, `customerProfileId`) |
| `GET /api/v1/me/profile`, `PUT /api/v1/me/profile` | any token | `account.read` / `account.write` | own customer profile |
| `GET /api/v1/me/reservations`, `GET /api/v1/me/reservations/{id}` | any token | `account.read` | own reservations only |
| `GET /api/v1/admin/settings/pickup`, `PUT /api/v1/admin/settings/pickup` | staff | `admin.write` (PUT) | pickup configuration |
| `POST /api/v1/admin/catalog/search/reindex` | staff | `admin.reindex` | rebuild the search index |
| `POST /api/v1/admin/products/{id}/images/uploads` | staff | `upload.request` | request an image upload ticket |
| `POST /api/v1/admin/products/{id}/images/uploads/{uploadId}/complete` | staff | `admin.write` | verify the upload and attach the image |
| `GET /api/v1/admin/products/{id}/images`, `PUT .../images/order`, `DELETE .../images/{imageId}` | staff | `admin.write` (writes) | list, reorder, delete (deletes the object) |
| `/api/v1/admin/categories` ... (8 endpoints) | staff | `admin.write` (writes) | category management, see "Staff admin API" |
| `/api/v1/admin/products` ... (6 endpoints) | staff | `admin.write` (writes) | product management |
| `/api/v1/admin/inventory` ... (6 endpoints) | staff | `admin.write` (writes) | stock positions, adjustments, movements |
| `/api/v1/admin/reservations` ... (9 endpoints) | staff | `admin.write` (writes) | reservation list, detail, lifecycle, notes |
| `/api/v1/admin/payments`, `.../reservations/{id}/payments|ledger|payment-adjustments|reconciliation` (8 endpoints) | staff | `admin.write` (writes) | payments, reversals, adjustments, ledger |
| `GET /api/v1/store/payment-options` | public | `catalog.read` | currencies payable online right now (`online: null` when Stripe is off), see docs/payments-stripe.md |
| `POST /api/v1/reservations/{reference}/payments/checkout-session` | public (reference + phone, or the owner's token) | `payment.checkout` | opens a Stripe-hosted checkout for ONE currency (`Idempotency-Key` required) |
| `POST /api/v1/reservations/{reference}/payments/status` | public (same proof) | `payment.status` | real payment state from the database, never from the browser |
| `POST /api/v1/webhooks/stripe` | Stripe signature | `webhook.receive` | verified Stripe events post money to the ledger |
| `GET /api/v1/admin/payment-attempts` | staff | - | online payment attempts, `requiresAttention` filter |
| `/api/v1/admin/customers` ... (3 endpoints) | staff | `admin.write` (PUT) | customers |
| `/api/v1/admin/reports/*` (6 endpoints) | staff | - | reports |

Default limits (all configurable, `ana.rate-limit.limits.*`): `catalog.read` 300/min, `catalog.search` 60/min, `reservation.submit` 10 per 10 min, `reservation.lookup` 20 per 10 min per client,
`reservation.lookup.reference` 30 per 10 min per reference, `account.read` 120/min, `account.write` 20 per 10 min, `upload.request` 30/min, `admin.write` 240/min, `admin.reindex` 3 per 10 min, `payment.checkout` 10 per 10 min, `payment.status` 60/min. The client key is the user id when a token
is present, else the client IP (`X-Forwarded-For` is trusted only from `ana.rate-limit.trusted-proxies`).

## Errors (RFC 9457 `application/problem+json`)
```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "Only 3 available, 5 requested", "code": "insufficient_stock", "correlationId": "5e9d2a8c-..." }
```
`code` is stable and machine-readable (translate it; `detail` is for developers). Field problems add `errors`:
```json
{ "status": 422, "code": "validation_failed", "detail": "The request has invalid fields.", "errors": [ { "field": "contact.phone", "code": "invalid" }, { "field": "acknowledgedTerms", "code": "terms_not_acknowledged" } ], "correlationId": "..." }
```
| Status | When | `code` examples |
|---|---|---|
| 400 | malformed JSON / wrong type / bean validation (`errors[{field,code}]`) | `bad_request`, `validation_failed` |
| 401 | no, invalid or expired token (body has no `correlationId`) | `unauthenticated` |
| 403 | valid token, not staff | `forbidden` |
| 404 | not found (also: another customer's reservation, another staff member's upload, an inactive/draft product) | `not_found`, `reservation_not_found` |
| 409 | state conflict | `insufficient_stock`, `idempotency_key_reuse`, `concurrent_update`, `invalid_status`, `data_conflict`, `duplicate_sku`, `duplicate_slug`, `inventory_exists`, `already_paid`, `already_reversed`, `payment_required`, `category_has_active_products`, `attribute_in_use`, `option_in_use`, `variant_axis_in_use`, `attribute_type_locked`, `attribute_required_violation`, `variant_in_use`, `product_in_use` |
| 422 | business rule or field validation | `validation_failed`, `idempotency_key_required`, `idempotency_key_invalid`, `pickup_disabled`, `pickup_not_configured`, `product_unavailable`, `variant_required`, `variant_unavailable`, `below_minimum_order`, `unsupported_currency`, `currency_required`, `search_query_too_long`, `filter_invalid`, `invalid_page`, `page_out_of_range`, `upload_type_not_allowed`, `upload_too_large`, `upload_missing`, `upload_type_mismatch`, `upload_signature_mismatch`, `upload_not_pending`, `upload_expired`, `images_order_invalid`, plus the staff codes listed under "Staff admin API" |
| 429 | rate limit; header `Retry-After` (seconds) | `rate_limited` |
| 503 | object storage down or not configured | `storage_unavailable`, `storage_not_configured` |
| 500 | unexpected (details never leaked) | `internal_error` |

## Pagination
`?page=0&size=20` (zero based, `size` 1-100, default 20; outside that: 422 `invalid_page`). Response `{ items, page, size, totalItems, totalPages }`.

## Idempotency
Header `Idempotency-Key: <8-128 characters of A-Z a-z 0-9 _ - : .>` (else 422 `idempotency_key_invalid`). **Required** on `POST /reservations` (422 `idempotency_key_required` when missing or blank); optional on the upload ticket request.
Scope: (actor, operation, key) where a guest's actor is `anonymous` and a signed-in customer's is `customer:<userId>`. The same key with the same payload returns the **original reservation** (HTTP 201 again, current state) and has no further effect;
the same key with a different payload is 409 `idempotency_key_reuse`. Retention 7 days. Generate one UUID per "submit" click and reuse it for every retry of that click.

---

## Catalog (public)

### `GET /categories` and `GET /categories/{slug}`
```json
{ "id": "…", "slug": "mattresses", "name": { "fr": "Matelas", "ht": "Matla" }, "description": {}, "imageUrl": null, "displayOrder": 1, "productCount": 12,
  "attributes": [ { "id": "…", "key": "size", "label": { "fr": "Taille" }, "type": "CHOICE", "unit": null, "required": false, "filterable": true, "variantAxis": true, "displayOrder": 1,
                    "options": [ { "id": "…", "code": "s", "label": { "fr": "S" }, "displayOrder": 1 } ] } ] }
```
`attributes` is empty in the list and filled in the detail. Only active categories and active attributes/options; `productCount` counts publicly visible products.

### `GET /products`, `GET /products/search`
Query parameters: `q`, `category` (slug), `currency` (`HTG|USD`), `minPrice`/`maxPrice` (aliases `min`/`max`), `wholesale` (bool), `inStock` (bool), `attr` (repeatable `attributeKey:optionCode`), `brand` (repeatable), `sort`
(`relevance` default with `q`, `newest` default without, `name`, `price-asc`, `price-desc`), `page`, `size`. Price filters and price sorts require `currency` (422 `currency_required`): HTG and USD are never compared. Full behaviour: [search.md](search.md).
Response: `{ items: [ProductResponse], page, size, totalItems, totalPages }`.

### `GET /products/{idOrSlug}` -> `ProductResponse` (404 for draft/inactive/unknown)
```json
{ "id": "…", "sku": "MAT-001", "slug": "matelas-confort", "categoryId": "…", "categorySlug": "mattresses",
  "name": { "fr": "Matelas", "ht": "Matla" }, "description": {}, "brand": null, "sellingUnit": "UNIT", "unitNote": {},
  "prices": [
    { "currency": "HTG", "retailPrice": "25000.00", "salePrice": null, "minOrderQuantity": 1, "wholesaleEnabled": true, "wholesaleTiers": [ { "minQuantity": 5, "unitPrice": "23000.00" } ] },
    { "currency": "USD", "retailPrice": "180.00",   "salePrice": null, "minOrderQuantity": 1, "wholesaleEnabled": true, "wholesaleTiers": [ { "minQuantity": 5, "unitPrice": "165.00" } ] } ],
  "variants": [ { "id": "…", "sku": "…", "axisValues": { "<attributeId>": "<optionId>" }, "retailPrices": [ { "currency": "HTG", "retailPrice": "30000.00" } ], "availability": "IN_STOCK", "availableQuantity": 12 } ],
  "images": [ { "id": "…", "url": "https://images.example/product-images/…/….jpg", "alt": {}, "position": 0 } ],
  "attributes": [ { "attributeId": "…", "optionId": "…", "text": null, "number": null } ],
  "availability": "IN_STOCK", "availableQuantity": 12, "createdAt": "2026-11-16T12:00:00Z" }
```
`availability`: `IN_STOCK`, `LOW_STOCK` (only when the seller set a threshold: no default policy is invented), `OUT_OF_STOCK` (also when there is no stock position), `UNTRACKED_AVAILABLE`. It is computed from the **shared** stock position
(`onHand - reserved`, never below 0) whatever the currency. `availableQuantity` is the number of units available **now** for tracked stock (for a product with variants: the sum over its available variants) and `null` for untracked stock;
`onHand` and `reserved` are never returned. Only active prices and variants are returned. Image `url`s are the public bucket URLs.

## Pickup

### `GET /store/pickup` (public)
```json
{ "status": "available", "unavailableMessage": null,
  "location": { "storeName": "…", "addressLine": "…", "city": "…", "region": "…", "country": "HT", "postalCode": null, "phone": "+509 …", "email": null,
                "additionalLocationInfo": { "fr": "", "ht": "" }, "schedule": [ { "day": 1, "open": true, "from": "09:00", "to": "17:00" } ], "closureInfo": { "fr": "", "ht": "" } },
  "instructions": { "general": {"fr": "", "ht": ""}, "contact": {…}, "accepted": {…}, "ready": {…}, "policy": {…} } }
```
`status`: `available` (location and instructions present), `disabled` (owner's public `unavailableMessage` only), `not_configured` (everything null). Reservations are refused (422 `pickup_disabled` / `pickup_not_configured`) unless it is `available`.
No pickup window, hold period or policy is invented: unset means unset.

### `GET /admin/settings/pickup`, `PUT /admin/settings/pickup` (staff)
`PickupSettings`: `{ enabled, unavailableMessage, location, instructions, holdPeriodDays, version, updatedAt, updatedBy }`. `version` is `null` until the first save; the PUT must echo the version it loaded (stale: 409 `concurrent_update`).
Invalid input: 422 `validation_failed` with `errors[{field, code}]` (`required`, `too_long`, `phone_invalid`, `email_invalid`, `country_invalid`, `schedule_one_day`, `time_invalid`, `schedule_hours`, `day_invalid`, `hold_period_invalid`). Audited; an outbox event is recorded.

## Reservations

### `POST /reservations` (public; `Idempotency-Key` required; optional bearer token)
Request:
```json
{ "lines": [ { "productId": "…", "variantId": null, "quantity": 2, "currency": "HTG" },
             { "productId": "…", "quantity": 1, "currency": "USD" } ],
  "contact": { "firstName": "Marie", "lastName": "Exemple", "dialCode": "+509", "phone": "3700 0000", "email": "marie@example.test" },
  "locale": "fr", "acknowledgedTerms": true }
```
Validation (422 `validation_failed`, all problems at once): `lines` 1-50 (`cart_empty`, `too_many_lines`); per line `productId` (`required`), `quantity` 1-10 000 (`quantity_invalid`), `currency` `HTG|USD` (`required`, `currency_invalid`);
`contact.firstName/lastName` required, max 100; `contact.dialCode` 1-4 digits with optional `+` (stored as `+<digits>`); `contact.phone` 6-15 digits, spaces and `()-./+` allowed; `email` optional and well formed;
`locale` `fr|ht`; `acknowledgedTerms` must be `true` (`terms_not_acknowledged`; the server stores the acknowledgement time and the version label configured in `ANA_TERMS_VERSION`, never a value sent by the client; the version is not exposed by any public endpoint yet). The same product may appear once per currency and shares one stock position.
Rules from the use case: every line priced from the catalog in its currency (retail, sale or the wholesale tier for that quantity), the product must be active and sold in that currency (`product_unavailable`, `price_not_available`), minimum order (`below_minimum_order`),
variants (`variant_required`, `variant_unavailable`), pickup available, stock reserved under row locks in a stable order: not enough -> **409 `insufficient_stock`** and nothing is reserved. Unknown product -> 404 `not_found`.

**201** `CustomerReservationView` (the same shape everywhere a customer sees a reservation):
```json
{ "id": "…", "reference": "ANA-K7M2QX", "status": "PENDING_REVIEW", "paymentStatus": "UNPAID", "locale": "fr",
  "createdAt": "2026-11-16T18:30:00Z", "readyAt": null, "expiresAt": null,
  "contact": { "firstName": "Marie", "lastName": "Exemple", "dialCode": "+509", "phone": "3700 0000", "email": "marie@example.test" },
  "lines": [ { "productId": "…", "variantId": null, "name": "Matelas", "sku": "MAT-001", "variantLabel": null, "unit": "UNIT", "quantity": 2, "currency": "HTG",
               "unitPrice": "25000.00", "pricingKind": "RETAIL", "tierMinQuantity": null, "lineTotal": "50000.00" } ],
  "totals":   [ { "currency": "HTG", "amount": "50000.00" }, { "currency": "USD", "amount": "180.00" } ],
  "balances": [ { "currency": "HTG", "total": "50000.00", "paid": "0.00", "due": "50000.00" }, { "currency": "USD", "total": "180.00", "paid": "0.00", "due": "180.00" } ],
  "pickup": { "location": { "storeName": "…" }, "instructions": { }, "capturedAt": "2026-11-16T18:30:00Z", "origin": "default" },
  "canCancel": false,
  "events": [ { "type": "SUBMITTED", "at": "2026-11-16T18:30:00Z", "note": null, "rejectReason": null, "cancelledBy": null } ] }
```
* `lines` are **snapshots**: name, SKU, variant label, unit, unit price, price kind and wholesale tier as they were; later catalog changes never alter them. `pricingKind`: `RETAIL | SALE | WHOLESALE` (`tierMinQuantity` set exactly for `WHOLESALE`).
  Images and slug are not snapshotted: load the product by `productId` (`GET /products/{id}`) when it is still published.
* `totals` and `balances` have **one entry per currency present**, sorted by currency; `paymentStatus` is `UNPAID | PARTIAL | PAID` (PAID only when every currency is settled). `pickup` is the pickup configuration **as promised at submission**.
* `status`: `PENDING_REVIEW, CONFIRMED, REJECTED, READY_FOR_PICKUP, COLLECTED, CANCELLED, EXPIRED`. `expiresAt` stays `null`: no hold period is decided.
* `events`: `type` in `SUBMITTED, ACCEPTED, REJECTED (rejectReason: PRODUCT_UNAVAILABLE|INCORRECT_PRODUCT_INFO|QUANTITY_UNAVAILABLE|CANNOT_FULFILL|OTHER), READY_FOR_PICKUP, COLLECTED, CANCELLED (cancelledBy: CUSTOMER|STORE), EXPIRED, NOTE`
  (a note the store marked customer-visible; `note` carries the text). **Never present:** internal notes, staff identities or user ids, ledger entries, payment references, staff-written rejection or cancellation text, customer ids.
* `canCancel` is always `false` and there is **no customer cancel endpoint**: the cancellation policy is an open business decision.
* A signed-in customer's token links the reservation to **their own** customer record (it appears in `GET /me/reservations`); a guest's links to a guest record per phone number. Guests are **not** merged into accounts, even with the same phone number or e-mail.
* Idempotent retry: 201 with the same `id` and `reference`.

### `POST /reservations/lookup` (public)
`{ "reference": "ANA-K7M2QX", "phone": "+509 3700 0000" }` -> **200** `CustomerReservationView`, or **404** `{ "status": 404, "code": "reservation_not_found", "detail": "No reservation matches this reference and phone number." }`.
* Both must match. The reference is case and whitespace insensitive. The phone is compared on **digits only**: the national number (`37000000`), or the dial code plus the national number (`+509 3700 0000`, `509 37000000`, `00509 37000000`).
  Fewer than 6 digits or a suffix of the number never matches (deliberately stricter than the storefront demo, which accepted any suffix).
* **Enumeration resistance:** an unknown reference, a malformed reference, a wrong phone and a too-short phone return the identical 404 (same status, code, title and detail; only `correlationId` differs).
  Missing/blank/too long (over 40 characters) fields are a 422 `validation_failed` (that reveals nothing). The phone travels in a POST body, never in a URL.
* Throttled twice: per client (`reservation.lookup`) and per reference whatever the client (`reservation.lookup.reference`, existing or not), so guessing one reference's phone from many addresses is stopped; 429 `rate_limited` + `Retry-After`.

## Customer account (any valid token; always the token's own user)

### `GET /me` -> `{ "userId", "email", "emailVerified", "staff", "permissions": [], "customerProfileId" }`
`customerProfileId` is the user's own customer record id or `null`. `permissions` is empty by design (roles are undecided); `staff` comes from the staff table, never from token claims; `emailVerified` is informational.

### `GET /me/profile`, `PUT /me/profile`
```json
{ "id": "…", "firstName": "Jean", "lastName": "Baptiste", "dialCode": "+509", "phone": "37000001", "email": "jean@example.test" }
```
`GET` before anything was saved: `{ "id": null, "firstName": null, ..., "email": "<the account e-mail>" }`. `PUT` body `{ firstName, lastName, dialCode, phone, email? }` creates the record or updates it in place (same `id`); same validation as the contact above
(422 `validation_failed` with all fields). `phone` is returned as digits only. Saving a profile never changes an existing reservation's contact snapshot.

### `GET /me/reservations?page&size`, `GET /me/reservations/{id}`
Page of `CustomerReservationView` (newest first), only reservations made **while signed in**; `{id}` of someone else's or a missing reservation: 404 `not_found` (identical).

## Staff: catalog search and images
* `POST /admin/catalog/search/reindex` -> `{ "indexed": 42 }` (idempotent, audited).
* **Upload ticket** `POST /admin/products/{productId}/images/uploads` body `{ "contentType": "image/jpeg", "sizeBytes": 123456 }` (types `image/jpeg|png|webp`, size 1 to `ana.upload.max-bytes`; optional `Idempotency-Key`) -> **201**
```json
{ "uploadId": "…", "productId": "…", "contentType": "image/jpeg", "maxBytes": 123456,
  "upload": { "url": "https://<account>.r2.cloudflarestorage.com/<bucket>/product-images/<productId>/<uuid>.jpg?X-Amz-…", "method": "PUT", "headers": { "Content-Type": "image/jpeg" }, "expiresAt": "2026-11-16T18:40:00Z" } }
```
  The browser must `PUT` **exactly `sizeBytes` bytes** with the returned headers to `url` before `expiresAt` (the type and length are part of the signature). The client never chooses the object key.
* **Complete** `POST /admin/products/{productId}/images/uploads/{uploadId}/complete` (no body) -> **200** `{ "id", "url", "alt": {}, "position" }`. The server inspects the stored object (size, type, file signature) and attaches the image; failures: 422 `upload_missing`, `upload_too_large`,
  `upload_type_mismatch`, `upload_signature_mismatch` (the object is deleted), `upload_not_pending`, `upload_expired`. Only the staff member who requested the upload can complete it, and only for that product (else 404). Repeating after success returns the same image.
* `GET /admin/products/{productId}/images` -> `{ "items": [ImageResponse] }`; `PUT .../images/order` body `{ "imageIds": [ every current id exactly once ] }` -> the new list (422 `images_order_invalid`; the first is the primary image);
  `DELETE .../images/{imageId}` -> 204 and the stored object is deleted (404 when the image is not that product's). Audited; completion records the outbox event `product.image.uploaded`, removal `product.image.removed`.

## Staff admin API
Every path below needs a staff token (anonymous 401, signed-in non-staff 403) and is rate limited as `admin.write` when it writes. Lists answer `{ items, page, size, totalItems, totalPages }`; filters combine with AND; an unknown enum value or malformed date is
422 `validation_failed` (`errors[{field, code: "invalid"}]`); `size` above 100 is 422 `invalid_page`; wildcard characters in a search text are plain text. **Every amount is a decimal string and every figure is per currency: HTG and USD are never summed, converted or compared.**
Writes are audited (`audit_events`); an audit entry never copies personal text (note bodies, customer names, e-mails).

**Optimistic concurrency.** Replacing endpoints (`PUT`, threshold `PATCH`) require the `version` you read in the body (missing: 422 `errors[{field:"version", code:"required"}]`); a stale version is 409 `concurrent_update` and changes nothing.
**Idempotency.** `Idempotency-Key` is optional on creates/transitions/notes (a retry with the same key and payload returns the first result; other payload: 409 `idempotency_key_reuse`) and **required** on stock adjustments and every payment write (422 `idempotency_key_required`).
Authorization is all-or-nothing staff membership: roles/permissions (who may record payments, view reports...) are an open business decision and are not modelled.

### Categories `/admin/categories`
| Call | Body / query | Result |
|---|---|---|
| `GET /admin/categories` | `page,size,active,q` (q: slug or fr/ht name) | page of `{ id, slug, name, imageUrl, active, displayOrder, attributeCount, productCount, activeProductCount, version }` in display order |
| `GET /admin/categories/{id}` | | detail: `{ id, slug, name{fr,ht}, description, imageUrl, active, displayOrder, version, createdAt, updatedAt, productCount, activeProductCount, attributes[{ id, key, label, type CHOICE\|TEXT\|NUMBER, unit, required, filterable, variantAxis, displayOrder, active, valueCount, options[{ id, code, label, displayOrder, active, useCount }] }] }` |
| `POST /admin/categories` | `{ slug, name, description?, imageUrl?, active?, displayOrder?, attributes? }` | **201** detail |
| `PUT /admin/categories/{id}` | the same plus `version`; attribute and option `id`s are kept so products keep pointing at them | detail |
| `PATCH /admin/categories/{id}/active` | `{ active, reason? }` | detail |
| `PUT /admin/categories/order` | `{ categoryIds: [every category exactly once] }` | `{ items: [summary] }` (422 `order_incomplete`) |
| `GET /admin/categories/{id}/products` | `page,size` | page of `{ id, sku, slug, name, brand, status, currencies[], variantCount, imageCount, updatedAt }` (every status) |
| `POST /admin/categories/{id}/products/move` | `{ toCategoryId, productIds: [1..200] }` | `{ moved }`, all or nothing; each product is re-validated against the target category |
Rules: an active category needs `name.fr` and `name.ht` (422 `errors[{name.ht, required_to_publish}]`); slug `[a-z0-9-]`, unique (409 `duplicate_slug`); `CHOICE` attributes need options, only they can be `filterable`/`variantAxis`. Protection of what products rely on: removing an attribute with values or variant axes
409 `attribute_in_use`; removing a chosen option 409 `option_in_use`; changing the type of an attribute holding values 409 `attribute_type_locked`; un-marking an axis used by variants 409 `variant_axis_in_use`; making an attribute required while active products lack it
409 `attribute_required_violation`; deactivating while active products exist 409 `category_has_active_products`; moving into an inactive category 422 `category_inactive`, a product not in the source category 422 `product_not_in_category`.
Audit: `CATEGORY_CREATED|UPDATED|ACTIVATED|DEACTIVATED`, `CATEGORIES_REORDERED`, `CATEGORY_PRODUCTS_MOVED`.

### Products `/admin/products`
| Call | Body / query | Result |
|---|---|---|
| `GET /admin/products` | `page,size,q,status,categoryId,brand,currency,stock,sort` | page of summaries |
| `GET /admin/products/{id}` | | detail |
| `POST /admin/products` | product body (below); `status` DRAFT (default) or ACTIVE | **201** detail |
| `PUT /admin/products/{id}` | product body + `version` | detail (images and status are kept) |
| `PATCH /admin/products/{id}/status` | `{ status: ACTIVE\|INACTIVE, reason? }` | detail |
| `DELETE /admin/products/{id}` | | 204 |
Filters: `q` is the PostgreSQL full-text query of the public search **plus** SKU / variant SKU / slug prefix (an exact SKU ranks first); `status` DRAFT\|ACTIVE\|INACTIVE; `currency` = has a price in that currency; `stock` IN_STOCK\|LOW_STOCK\|OUT_OF_STOCK\|NO_STOCK_RECORD;
`sort` RELEVANCE (default with `q`)\|NEWEST (default)\|UPDATED\|NAME\|SKU.
Product body: `{ sku?, slug?, categoryId, name{fr,ht}, description?, brand?, unit (UNIT\|PAIR\|PACK\|BOX\|SET\|METER\|KILOGRAM), unitNote?, status?, prices: [{ currency, retail, sale?, minOrderQuantity?, wholesaleEnabled?, tiers: [{ minQuantity, unitPrice }], active? }],
variants: [{ id?, sku, active?, position?, axisValues: { <attributeId>: <optionId> }, retailOverrides?: { HTG?: "…", USD?: "…" } }], attributes: [{ attributeId, optionId \| text \| number }] }` - amounts are decimal text; **each currency has its own price block** (a currency without a price is simply not sold in it);
a missing `slug` is derived from the name (numbered when taken). Detail adds `{ id, version, createdAt, updatedAt, images[{id,url,alt,position}], stock{ onHand, reserved, available, hasUntrackedPosition, state, positions[…] } }` (stock figures are staff-only; the public API exposes `availableQuantity` only).
Rules and codes: structural problems are one 422 `validation_failed` listing every field (`prices[0].retail`, `variants[1].sku`...); price rules 422 `retail_price_invalid`, `sale_price_invalid`, `tier_*`, `wholesale_disabled_with_tiers`, `price_required` (publishing); 409 `duplicate_sku` (also against another product's variant SKUs),
`duplicate_slug`; a published product needs `name.fr` and `name.ht` (422 `required_to_publish`); a variant that has a stock record or reservation lines cannot be removed, only deactivated (409 `variant_in_use`); status lifecycle DRAFT to ACTIVE, ACTIVE to INACTIVE, INACTIVE to ACTIVE
(409 `invalid_status`); `DELETE` only for a DRAFT without images, stock record or reservation lines (409 `product_in_use`). Reservations already made keep their snapshot whatever happens to the product.
Audit: `PRODUCT_CREATED|UPDATED|DELETED|ACTIVATED|DEACTIVATED`, `PRODUCT_PRICE_CHANGED`, `PRODUCT_PRICE_REMOVED`, `PRODUCT_STATUS_CHANGED`. Images: the upload endpoints above.

### Inventory `/admin/inventory` (stock is shared by HTG and USD)
| Call | Body / query | Result |
|---|---|---|
| `GET /admin/inventory` | `page,size,productId,categoryId,state OK\|LOW\|OUT\|UNTRACKED,q,sort UPDATED\|AVAILABLE_ASC` | page of positions |
| `GET /admin/inventory/{id}` | | one position |
| `POST /admin/inventory` | `{ productId, variantId?, tracked?=true, initialOnHand?=0, lowStockThreshold? }` | **201** position |
| `POST /admin/inventory/{id}/adjustments` | `{ type ADD\|REDUCE\|RECOUNT, quantity, reason RECEPTION\|RETURN\|DAMAGE\|LOSS\|CORRECTION\|COUNT\|OTHER, note? }` + **required** `Idempotency-Key` | **201** movement |
| `PATCH /admin/inventory/{id}/threshold` | `{ version, lowStockThreshold }` (null clears) | position |
| `GET /admin/inventory/{id}/movements` | `page,size` | movements, newest first |
Position: `{ id, productId, variantId, productSku, productName, productStatus, categoryId, variantSku, tracked, onHand, reserved, available, lowStockThreshold, state, version, updatedAt }` (`onHand/reserved/available` are null when untracked). `available = onHand - reserved`; reservations own `reserved`; an adjustment never touches it.
Movement: `{ id, inventoryItemId, type INITIAL\|ADD\|REDUCE\|RECOUNT\|SALE, delta, balanceAfter, reason, reservationId, note, at, recordedBy }` (append-only). Codes: 409 `inventory_exists`; 422 `variant_required` (a product with variants is stocked per variant), `stock_negative`, `stock_invalid`,
`inventory_not_tracked`, `idempotency_key_required`; quantities are bounded (0..10,000,000). Audit: `INVENTORY_REGISTERED`, `INVENTORY_ADJUSTED`, `INVENTORY_THRESHOLD_CHANGED`. A product without a stock record cannot be reserved: register one.

### Reservations `/admin/reservations`
| Call | Body / query | Result |
|---|---|---|
| `GET /admin/reservations` | `page,size,status,paymentStatus UNPAID\|PARTIAL\|PAID,currency,q,productId,customerId,from,to (dates or instants),sort NEWEST\|OLDEST` | page of `{ id, reference, status, paymentStatus, createdAt, contact, lineCount, unitCount, balances[{currency,total,paid,due}] }` |
| `GET /admin/reservations/{id}` | | detail |
| `POST /admin/reservations/{id}/accept` | | detail |
| `POST /admin/reservations/{id}/reject` | `{ reason PRODUCT_UNAVAILABLE\|INCORRECT_PRODUCT_INFO\|QUANTITY_UNAVAILABLE\|CANNOT_FULFILL\|OTHER, note? }` (OTHER needs a note) | detail, stock released |
| `POST /admin/reservations/{id}/ready` | `{ expiresAt? }` (no hold period is configured: null by default) | detail |
| `POST /admin/reservations/{id}/collect` | | detail; needs every currency paid (409 `payment_required`); deducts stock |
| `POST /admin/reservations/{id}/cancel` | `{ reason }` | detail, stock released |
| `POST /admin/reservations/{id}/notes` | `{ visibility INTERNAL\|CUSTOMER_VISIBLE, body (<= 2000) }` | **201** detail |
`q` matches the reference (prefix), names, e-mail, phone digits. Detail: `{ id, reference, status, paymentStatus, locale, createdAt, readyAt, expiresAt, customerId, contact, lines[…snapshot…], balances[], pickup{…snapshot…}, terms{ version, acceptedAt }, history[{from,to,reasonCode,reasonNote,at,actor{kind,userId}}],
notes[{id,visibility,body,at,author}], allowedActions[ACCEPT|REJECT|MARK_READY|COLLECT|CANCEL|ADD_NOTE] }`. Transitions run in one transaction (lock, stock, audit, outbox) and accept an optional `Idempotency-Key`; a transition not allowed from the current status is 409 `invalid_status`. Internal notes never reach customers;
customer-visible ones appear in the customer's view. Audit: `RESERVATION_ACCEPTED|REJECTED|READY|COLLECTED|CANCELLED`, `RESERVATION_NOTE_ADDED`.

### Online payments (Stripe Checkout)
Contract, lifecycle, webhook events, configuration and what was verified: **docs/payments-stripe.md**. Staff `POST /admin/reservations/{id}/payments` refuses `method=STRIPE_CHECKOUT` (422 `method_not_allowed`);
reversing a Stripe payment is 409 `online_payment_reversal_unsupported`. New codes: 422 `online_payment_unavailable`, `currency_not_available_online`, `amount_below_minimum`; 409 `payment_in_progress`; 502 `payment_provider_error`. `GET /admin/payments?method=STRIPE_CHECKOUT` lists them (`reference` = PaymentIntent id).

### Payments (cash at pickup, and the ledger for every method)
| Call | Body / query | Result |
|---|---|---|
| `GET /admin/payments` | `page,size,reservationId,currency,method,from,to,reversed,recordedBy` | page of payments (newest received first) |
| `GET /admin/payments/{id}` | | payment |
| `POST /admin/reservations/{id}/payments` | `{ amount, currency, method?=CASH_AT_PICKUP, receivedAt?, reference?, note? }` + **required** key | **201** `{ payment, paymentStatus, balances }` |
| `POST /admin/payments/{id}/reversal` | `{ reason }` + **required** key | `{ payment (reversed:true), paymentStatus, balances }` |
| `GET /admin/reservations/{id}/payments` | | `{ paymentStatus, balances[], payments[] }` |
| `GET /admin/reservations/{id}/ledger` | | entries `{ id, type PAYMENT_RECEIVED\|PAYMENT_REVERSAL\|ADJUSTMENT, amount (signed), currency, paymentId, reversesEntryId, reason, at, createdBy, correlationId }`, oldest first |
| `POST /admin/reservations/{id}/payment-adjustments` | `{ amount (signed), currency, reason }` + **required** key | **201** ledger entry |
| `GET /admin/reservations/{id}/reconciliation` | | `{ reservationId, consistent, lines[{currency, cachedPaid, ledgerPaid, consistent}] }` |
A payment settles only its own currency; the reservation is PAID when every currency is settled. Payments, reversals and adjustments are accepted only while the reservation is READY_FOR_PICKUP (409 `invalid_status`). History is append-only: a reversal or adjustment is a NEW entry.
Codes: 409 `already_paid` (that currency is settled), `already_reversed`; 422 `currency_invalid` (the reservation has no balance in it), `reason_required`, `paid_negative`, `ledger_amount_zero`, `idempotency_key_required`, field issues for amounts (`invalid`, `too_many_decimals`, `must_be_positive`).
**Open decisions kept as they were:** an over-payment is still accepted (the due amount floors at zero); there is no partial-payment policy; who may record payments is not restricted beyond staff membership. Audit: `PAYMENT_RECORDED|REVERSED|ADJUSTED`.

### Customers `/admin/customers`
* `GET /admin/customers?page,size,kind REGISTERED|GUEST,q,from,to,sort NEWEST|NAME` -> page of `{ id, kind, firstName, lastName, dialCode, phone, email, hasAccount, createdAt, updatedAt, version, activity{ reservations, firstReservationAt, lastReservationAt }, summary: null }`.
* `GET /admin/customers/{id}` -> the same with `summary{ reservationsByStatus{…}, collectedByCurrency{HTG,USD}, openDueByCurrency{HTG,USD} }` (per currency, never summed). A customer's reservations: `GET /admin/reservations?customerId=`.
* `PUT /admin/customers/{id}` body `{ version, firstName, lastName, email? }` -> corrects a GUEST's name and e-mail. The phone (a guest's identity) and the account link are never edited; a registered customer's profile belongs to its owner (422 `customer_managed_by_owner`);
  reservations keep the contact they were made with. Guests and registered customers are separate records and are never merged. Audit `CUSTOMER_UPDATED` lists the changed field names only.

### Reports `/admin/reports`
Query: `from`, `to` (`yyyy-MM-dd`, both inclusive; default: the last 30 days), `timezone` (IANA, default `America/Port-au-Prince`); at most 366 days (422 `period_too_long`, `date_range_invalid`). All read-only; every amount is per currency (`{ "HTG": "…", "USD": "…" }`).
* `GET /admin/reports/overview` -> `{ period, reservationsCreated{status:n}, openNow{PENDING_REVIEW,CONFIRMED,READY_FOR_PICKUP}, collected{cur}, paymentsNet{cur}, stock{ok,low,out,untracked,activeProductsWithoutStockRecord}, activeProducts, newCustomers }`.
* `GET /admin/reports/sales?groupBy=DAY|WEEK|MONTH` -> `{ period, groupBy, buckets[{ start, collected{cur}, reservations }], totals{cur}, reservations }`; a **sale** is a reservation that was COLLECTED, dated by its collection; empty buckets are included.
* `GET /admin/reports/payments?groupBy=` -> `{ period, groupBy, buckets[{ start, received, reversed (positive), adjustments (signed), net }], totals }`, from the ledger entry dates.
* `GET /admin/reports/products?currency=&limit=10` -> `{ period, rows[{ productId, name, sku, currency, quantity, revenue, reservations }] }`, ranked inside each currency by revenue then units, `limit` (1-100) rows per currency; names are the reservation snapshots.
* `GET /admin/reports/reservations` -> `{ period, byStatus, total, rejectionReasons{code:n} }` (reservations created in the period, by current status).
* `GET /admin/reports/stock` -> `{ stock{…}, activeProducts }` (now). Not implemented: stock valuation (no cost data exists), average processing times.

## Concurrency
Stock: `SELECT ... FOR UPDATE` on the inventory rows in a stable order inside the reservation transaction (ADR 0009); losers get 409 `insufficient_stock` (tested over HTTP with simultaneous requests for the last units across both currencies). Optimistic `version` columns
guard admin edits (409 `concurrent_update`; tested with simultaneous requests: exactly one wins). Reservation transitions and payments lock the reservation row (`FOR UPDATE`): of simultaneous accept/reject calls exactly one succeeds. Unique constraints back idempotency, SKUs and customer identities.
