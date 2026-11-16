# Catalog search (PostgreSQL full-text search)

Decision record: [ADR 0015](adr/0015-postgresql-full-text-search.md). This page describes what is implemented, how to use it, its limits, and measured performance.
No external search engine is used; everything runs on the application's PostgreSQL (Supabase).

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/products/search` | public | full-text search + filters |
| GET | `/api/v1/products` | public | same use case; with `q` it is the same search, without `q` it is a filtered listing (newest first) |
| POST | `/api/v1/admin/catalog/search/reindex` | staff | rebuilds every product's `search_vector` (idempotent, audited, 3 per 10 min) |

Only publicly discoverable products are returned: `status = ACTIVE`, active category, at least one active price.

### Query parameters

| Parameter | Meaning |
|---|---|
| `q` | free text; max 100 characters (`422 search_query_too_long`), at most 8 terms after dropping 1-character noise |
| `category` | category slug (an unknown slug returns an empty page, never an error) |
| `currency` | `HTG` or `USD`; required for any price filter or price sort (`422 currency_required`) |
| `minPrice`/`maxPrice` (aliases `min`/`max`) | inclusive range, compared only within `currency` |
| `wholesale` | `true` restricts to products with a wholesale tier in `currency` |
| `inStock` | `true` keeps products that are not stock-tracked or have `onHand - reserved > 0` (numbers are never exposed) |
| `attr` | repeatable `attributeCode:optionCode`; values of one attribute are OR, different attributes are AND |
| `brand` | repeatable; OR |
| `sort` | `relevance` (default with `q`), `newest` (default without `q`), `name`, `price-asc`, `price-desc` |
| `page`, `size` | zero-based page; offsets beyond 10 000 are refused (`422 page_out_of_range`) |

Rate limit `catalog.search` per client (429 + `Retry-After`).

## How a query is processed

1. **Normalisation** (`SearchText`): NFD, accents removed, lower case, anything that is not a letter or digit becomes a separator. `Chèz Anpile` and `chez anpile` are the same query.
   The same function builds the indexed text, so index and query always agree.
2. **Terms**: split on separators, 1-character terms dropped (unless they are the only term), plural fold (a term of 4+ letters ending in `s` or `x` loses that letter), each term becomes a prefix `term:*`,
   terms are joined with `&` (AND). The resulting tsquery is passed to PostgreSQL as a **bound parameter**; user text never reaches SQL as syntax. Control characters (including NUL) are stripped.
3. **Index**: `products.search_vector` (tsvector, `simple` configuration, GIN index `products_search_vector_idx` with `fastupdate = off`).
   Weights: A product names (all languages); B SKU, variant SKUs, brand, category names; C descriptions; D attribute option labels, text/number attribute values, variant axis labels.
4. **Ranking**: exact SKU or variant SKU match first, then `ts_rank_cd(vector, query, 32)`, then newest, then id.

The vector is refreshed inside the transaction of every product or category save by the catalog use case (a rolled-back save leaves the previous vector). `POST /admin/catalog/search/reindex` is the repair tool and
should be run once after deploying V8 on a database that already has products (the SQL backfill in V8 cannot fold accents without an extension).

## Haitian Creole and French: limitations

- Matching is exact-token-prefix after accent folding. There is no Creole stemmer, no synonym table, no typo tolerance.
- Creole spelling variants that differ by letters rather than accents (for example `kòd` and `kod` fold together, but `kod` and `cord` do not) are not unified. A variant table needs real vocabulary from the seller; none was invented.
- The plural fold is a heuristic for regular `-s`/`-x` plurals. It can over-match rarely (`tapis` finds `tapi...`) and does not handle irregular plurals (`cheval`/`chevaux`).
- A product in French is found by a Creole query only when the seller entered the Creole text (all localized fields are indexed together).
- Multi-term search is AND of prefixes; there is no phrase search or "OR" mode.

## Measured performance

Opt-in test `CatalogSearchPerformanceTest` (run with `mvn test -Dtest=CatalogSearchPerformanceTest -Dsearch.perf=true`; skipped by default). It generates **20 000 products** (French/Creole names,
descriptions, brands, categories, HTG and USD prices, wholesale tiers, variants, stock), indexes them through the real use case, then times the full use case (search query + loading the page of 24 + stock) 200 times per scenario after 20 warm-up runs.
Environment: Testcontainers PostgreSQL 17 on a developer laptop, warm cache, local socket. These are indicative, not a production guarantee; the 125 s generation time is test data creation through the use case (about 6 ms per product including indexing).

| Scenario | Matches | p50 (ms) | p95 (ms) | max (ms) |
|---|---|---|---|---|
| single term `brouette` | 1049 | 47.5 | 71.1 | 102.8 |
| prefix `matel` | 2596 | 60.1 | 136.9 | 209.4 |
| three terms `table moderne bois` | 23 | 11.6 | 15.8 | 18.2 |
| Creole `chèz anpile` | 171 | 17.1 | 23.7 | 27.5 |
| single term + USD range + in stock | 769 | 36.5 | 48.8 | 65.0 |
| text + wholesale HTG + sort price asc | 661 | 26.9 | 45.9 | 60.2 |
| empty text listing (newest) | 20000 | 28.5 | 38.7 | 53.0 |
| empty text + in stock + USD | 11239 | 33.1 | 81.3 | 211.6 |

A first run of the same test measured the same scenarios 20-45% faster (for example single term p50 26.8 ms, p95 44.0 ms): run-to-run variance on a laptop is large, so treat the table as an order of magnitude (tens of ms).

`EXPLAIN (ANALYZE, BUFFERS)`:
- single term (`brouette`, 1049 matches): Bitmap Index Scan on `products_search_vector_idx` (3 buffers, 0.2 ms), Bitmap Heap Scan (811 blocks), top-N heapsort for `LIMIT 24`; execution **12.3 ms**.
  The cost is dominated by ranking every matching row (`ts_rank_cd` on 1049 vectors), as expected: ranking is O(matches), not O(catalog).
- three terms (23 matches): Bitmap Index Scan, 34 buffers, execution **2.0 ms**.

Reading the numbers: the database portion is 2-12 ms; the rest of the end-to-end time is page loading (JPA batch fetch of products, prices, images, stock). Queries that match most of the catalog (empty text listing)
cost the same order as a normal category page because no ranking is needed.

### GIN `fastupdate`

With the default `fastupdate = on`, new entries sit in a pending list that is merged lazily; while the list is large the planner (and queries) pay for scanning it, which made the index plan flaky right after bulk
changes. V8 creates the index with `fastupdate = off`: slightly slower writes (one product update per save, negligible for a single seller), stable reads. Keep it.

### Plan test

`CatalogSearchPlanIntegrationTest` asserts that the GIN index is usable for the search predicate (with `enable_seqscan = off` in the transaction) and prints the natural plan. At 2 500 rows a term matching
5% of rows legitimately plans a sequential scan, so the test does not assert the natural plan; the 20 000-row measurement above shows the natural plan using the index.

## Tests

`CatalogFullTextSearchIntegrationTest` (21): accents, case, plural fold, prefix, Creole, multi-term AND, ranking (name over description), SKU exact first, filters (category, brand, attributes, currency range,
wholesale, in stock), eligibility (draft/inactive excluded), index refresh on product/category/attribute edits, rollback safety, injection and NUL input, limits, reindex. `SearchQueryTest` (6) covers the parser.
`CatalogSearchRateLimitIntegrationTest` (2) covers 429. All run on a real PostgreSQL (Testcontainers), not an in-memory substitute.

## Adding searchable data

Any new code path that changes searchable data must go through the catalog use cases (they call `ProductSearchIndex.index`). Direct SQL updates to `products`/`categories` do not refresh the vector; run the
reindex endpoint afterwards.
