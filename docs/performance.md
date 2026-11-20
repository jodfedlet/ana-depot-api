# Performance report (1-second target)

Measured 2026-11-18 with `ana-depot-web/scripts/measure-latency.mjs` (30 sequential samples per route after one discarded warm-up; complete HTTP operations).
**Local measurement only.** It says nothing about a hosted deployment, TLS, a mobile network, a cold start, concurrent load or a database of production size.

## Environment
| | |
|---|---|
| Machine | 12 logical CPUs (13th Gen Intel Core i7-1355U), 15.7 GiB RAM, Linux 7.0; **shared with the browser, an IDE and other containers** (load average about 5 during the run) |
| API | Spring Boot 4.1.2, Java 25.0.1 (Temurin), the packaged jar, default Hikari pool (10), production-like settings except relaxed rate limits |
| Database | PostgreSQL 17.6 (local Supabase image), same machine, loopback |
| Web | Next.js 16.4.6 production build (`next start`), Node 24.11, server-rendered pages with the cached catalog layer |
| Network | loopback, no TLS, no proxy |
| Dataset | seeded verification catalog: 20 products, 16 reservations before the run (the write probes add 31 reservations); the search at scale is measured separately below |
| Target | p95 <= 1000 ms per route (`TARGET_MS`) |

## Result: 0 of 34 routes exceed the target
Milliseconds. `bad` = responses with an unexpected status (none).

| | Route | p50 | p95 | max | bad |
|---|---|---|---|---|---|
| API | GET /products?size=20 | 10 | 22 | 29 | 0 |
| API | GET /products (filters) | 11 | 15 | 16 | 0 |
| API | GET /products/search?q= | 5 | 6 | 7 | 0 |
| API | GET /products/{slug} | 7 | 12 | 13 | 0 |
| API | GET /categories | 5 | 8 | 9 | 0 |
| API | GET /categories/{slug} | 4 | 5 | 5 | 0 |
| API | GET /store/pickup | 3 | 4 | 5 | 0 |
| API | GET /store/payment-options | 2 | 3 | 3 | 0 |
| API | admin GET /reservations | 6 | 8 | 8 | 0 |
| API | admin GET /reservations/{id} | 5 | 6 | 6 | 0 |
| API | admin GET /products | 9 | 11 | 11 | 0 |
| API | admin GET /inventory | 10 | 14 | 23 | 0 |
| API | admin GET /payments | 5 | 8 | 11 | 0 |
| API | admin GET /customers | 7 | 11 | 11 | 0 |
| API | admin GET /reports/overview | 8 | 11 | 12 | 0 |
| API | admin GET /reports/payments | 6 | 10 | 11 | 0 |
| API | admin GET /staff | 6 | 9 | 9 | 0 |
| WEB | /fr (home) | 8 | 24 | 40 | 0 |
| WEB | /fr/products | 26 | 35 | 36 | 0 |
| WEB | /fr/products/{slug} | 8 | 9 | 9 | 0 |
| WEB | /fr/categories/{slug} | 10 | 23 | 30 | 0 |
| WEB | /fr/cart | 10 | 12 | 13 | 0 |
| WEB | /ht (home) | 13 | 26 | 38 | 0 |
| WEB | /ht/products | 44 | 61 | 64 | 0 |
| WEB | /ht/products/{slug} | 14 | 16 | 16 | 0 |
| WEB | /ht/categories/{slug} | 13 | 29 | 33 | 0 |
| WEB | /ht/cart | 13 | 16 | 16 | 0 |
| WEB | /sitemap.xml | 5 | 8 | 21 | 0 |
| API | POST /reservations (submit) | 44 | 64 | 73 | 0 |
| API | POST /reservations/lookup (tracking) | 21 | 36 | 39 | 0 |
| API | admin POST accept | 30 | 51 | 57 | 0 |
| API | admin POST ready | 31 | 47 | 54 | 0 |
| API | admin POST record cash payment | 44 | 64 | 75 | 0 |
| API | admin POST collect | 37 | 52 | 66 | 0 |

The slowest p95 is 64 ms (Haitian Creole products page, which renders a 12-product page server-side; and reservation submission / cash payment, which lock stock and write the ledger, the outbox and the audit trail in one transaction). Nothing is near the target, so no route needed investigation. The write probes are complete operations: reservation submission (price re-computation, stock lock, snapshot, outbox, audit), tracking lookup (phone matching), and the four staff operations accept, ready, record cash payment, collect.

## Search at scale (use-case level, Testcontainers PostgreSQL 17)
`CatalogSearchPerformanceTest` (opt-in) over **20,000 products**: eight scenarios (single term, prefix, three terms, Creole, price range + stock, wholesale + sort, plain listing, in stock + USD): worst p95 **107.6 ms** (prefix search), worst max 198 ms. This exercises the heaviest public read path with an index, not the HTTP stack.

## Query efficiency
`QueryCountIntegrationTest` asserts that every list endpoint (catalog, search, categories, admin reservations, customers, products, inventory, payments, the customer's own reservations) issues a **fixed number of SQL statements** at two data sizes: no N+1.

## Not measured (do before launch)
* Latency under concurrent load (the tool is sequential), and p95 under the sizes a real catalog will reach (thousands of reservations and payments).
* Hosted latency: TLS handshake, Cloudflare edge, the distance between the API runtime and the Supabase region, cold starts (a JVM needs 18 to 100 s depending on CPU, see `docs/staging/cloudflare-staging-plan.md`), mobile networks.
* Pages rendered for an authenticated customer or staff member on a hosted deployment.
Re-run `node scripts/measure-latency.mjs` against staging once it exists (the guard allows a dedicated remote test box with `E2E_ALLOW_NON_LOOPBACK=1`, never a hosted-looking name).

## Concurrency (MVP closure phase, 2026-11-19) - `ana-depot-web/scripts/measure-concurrency.mjs`
LOCAL measurement only (12-CPU developer machine that was also running Supabase, Postgres, MinIO, Mailpit and other work: load average about 9; relaxed rate limits so one machine can generate the load). It says nothing about a hosted deployment, TLS, a mobile network or a cold start.

**A. Read load** (a mix of 6 API routes and 3 server-rendered pages, 10 s per level):
| Users | req/s | p50 | p95 | p99 | errors | 5xx |
|---|---|---|---|---|---|---|
| 10 | 67 | 62 ms | 714 ms | 960 ms | 0 | 0 |
| 25 | 86 | 85 ms | **1,371 ms** | 2,476 ms | 0 | 0 |
| 50 | 70 | 78 ms | 3,871 ms | 4,446 ms | 0 | 0 |

Per route at 25 users: **every API route p95 <= 183 ms** (payment options 85, pickup 92, search 147, categories 123, product 183, listing 164). The server-rendered pages are the problem: home p95 1,192 ms, product page 757 ms, `/ht/products` 2,506 ms. During the load the Next.js process used about 145% CPU and the API about 16%: **one Next.js process saturates at about 22-25 requests per second on its heaviest page**, so the 1-second target is NOT met for the server-rendered pages at 25 or more simultaneous visitors on this machine. The API and the database are not the bottleneck.
Done: a public page used to carry the whole administration dictionary twice (230 KB of HTML); now 178 KB and no staff wording (test `storefront-messages.test.ts`). It did not change the saturation point.
**Needed before launch (not done, outside this phase):** run more than one web instance behind the proxy, cache the public catalog pages (revalidated server rendering or a CDN), and re-measure on dedicated hardware and on the real hosting. The staging plan (`staging/cloudflare-staging-plan.md`) is where that gets decided.

**B. Contention invariants - all hold:**
* No overselling: 65 simultaneous reservations for 40 units -> exactly 40 reserved, 25 refused, reserved stock +40, on-hand unchanged (earlier runs: 125 for 100, 105 for 80).
* One `Idempotency-Key` sent by 12 clients at once -> exactly one reservation.
* 20 reservations moved through accept, ready, cash payment (full balance) and collect at the same time: every step succeeded for all 20 (p95 294 / 346 / 689 / 726 ms), every ledger reconciled, every reservation ended COLLECTED.
* Eight simultaneous payments of a quarter of the balance: exactly four recorded, paid = total (D3).
* No server error (5xx) anywhere.
