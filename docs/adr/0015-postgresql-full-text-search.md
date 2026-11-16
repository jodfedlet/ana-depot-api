# ADR 0015 - Catalog search with PostgreSQL full-text search
Status: accepted (implemented and tested; performance measured, see docs/search.md).

**Context.** The storefront needs product search over French and Haitian Creole text, tolerant of accents and plurals, prefix-friendly ("matel" finds
"matelas"), ranked by relevance, combinable with category, availability, currency + price and wholesale filters, and limited to publicly discoverable products
(ACTIVE, in an active category, with an active price). Search must run on the existing PostgreSQL (Supabase); no external engine is allowed.
The first implementation (V7, `LIKE '%token%'` on a text column) was only a stop-gap: no ranking, no index support, wildcards in user text.

**Decision.**
1. **A `tsvector` column `products.search_vector` with a GIN index** (V8; the LIKE column is dropped, data-preserving). Weights: **A** names in every language, **B** SKU, variant SKUs,
   brand and category name (every language), **C** descriptions, **D** attribute option labels (every language), attribute text/number values, variant axis labels.
2. **The `simple` text-search configuration over application-normalised text.** `SearchText` folds accents (NFD, strips marks), lower-cases, and keeps only letters and digits
   ("SKU-AB12" becomes `sku ab12`). The same normalisation is applied to indexed text and to query terms, so French, Haitian Creole and mixed text behave identically and no extension is
   needed (`unaccent` / `pg_trgm` exist on Supabase but are not required and their availability in a migration is not guaranteed). The built-in `french` configuration was rejected as the primary
   configuration: it stems with a French dictionary and would mangle Creole words (there is no Creole dictionary in PostgreSQL), and indexing each language with its own configuration needs per-field
   language tagging that the seller's free-form localized text does not have.
3. **Prefix matching on every term, AND across terms.** The query becomes `tok1:* & tok2:*` and is passed as a **bound parameter** to `to_tsquery('simple', ...)`. Only letters and digits survive
   normalisation, so the text contains no tsquery operator (`& | ! ( ) : * ' " \ <->` are word separators). At most 100 characters and 8 terms (`search_query_too_long`); one-character terms are noise
   unless alone; control characters never reach the database.
4. **No dictionary stemming; a minimal French plural fold at query time instead.** A term that ends in `s`/`x` and has 4+ letters is searched without that ending, as a prefix:
   "chaises" finds "chaise" and "chaises" ("chaise:*" matches both). A second `french` vector would add a GIN index and write cost, change relevance in ways that are hard to explain, and still not help Creole;
   the heuristic covers the dominant case (regular plurals) for both languages at no storage cost. It will over-match in rare cases ("bus" is protected by the length rule; "tapis" matches "tapi...").
5. **Ranking:** an exact (case-insensitive) SKU or variant-SKU match first, then `ts_rank_cd` (cover density, normalisation 32) over the weighted vector (default weights D 0.1, C 0.2, B 0.4, A 1.0: a name match outranks a description match, which outranks an attribute match), then newest, then id (deterministic).
   Other sorts: newest, name, price-asc / price-desc (only with a currency, within that currency).
6. **The index is maintained by the core catalog use cases, inside the transaction of the change** (`saveProduct`, and `saveCategory` which refreshes every product of the category - covering category renames
   and attribute option label changes). A rolled-back change leaves the old vector (tested). Generated columns could not read other tables (category, attributes, variants), and triggers would hide business
   logic from core; the use cases already hold the loaded aggregates. `POST /api/v1/admin/catalog/search/reindex` rebuilds everything (idempotent, audited, rate limited 3 per 10 minutes) for repair and backfill;
   V8 includes a safe SQL backfill for existing rows (no accent folding is possible without an extension, so run the reindex after deploying).
7. **Native SQL, confined to one class** (`infra.catalog.ProductSearchNative`). AGENTS.md allows native SQL only as a last resort with a documented technical justification: Hibernate 7.4 has no tsvector
   type, no `@@`, no `ts_rank_cd`, no `setweight`, no `to_tsquery` (verified in hibernate-core), so ranked full-text search cannot be written in JPQL/Criteria. It runs through the JPA `EntityManager`
   (no JdbcTemplate, no raw JDBC); all SQL text is built from fixed fragments and **every value is a bound named parameter**. **All** public catalog searches, with or without a text, run in this one class so the
   filter rules (currency-aware price range/sort, wholesale, in stock, attributes, brand, category) exist once; products are then loaded by id in one JPA query with batch-fetched collections (no N+1), keeping the page
   order of the native query. Results deeper than offset 10 000 are refused (`page_out_of_range`) and each query has a 5 s timeout.
8. **Eligibility is part of the query:** `status = ACTIVE`, the category is active, and the product has at least one active price. Currency filters and sorts compare a single currency's price row only;
   HTG and USD amounts are never compared. Availability is not part of ranking; `inStock=true` filters on the shared stock (`not tracked or on_hand - reserved > 0`) and the response carries per-product and per-variant availability.

**Haitian Creole limitations (honest).** Matching is exact-token-prefix after accent folding: no Creole stemming, no synonym handling, and no normalisation of orthographic variants (Creole spelling is not fully
standardised: "chèz"/"chez" fold together, but "kòd"/"kod" vs "cord" do not). A documented, small variant table could be added later if the owner supplies real vocabulary; none is invented here.
Mixed-language text works because every language of the localized fields is indexed together.

**Alternatives.** External engines (Elasticsearch, Algolia, Meilisearch, Typesense): excluded by requirement and unnecessary at this scale. `pg_trgm` similarity: good for typos but needs the extension and a larger index;
could be added later for "did you mean". The `french` configuration with stemming: see 2 and 4. Generated `tsvector` column or triggers: cannot or should not reach other tables (see 6).

**Consequences.** Search is fast and ranked with only PostgreSQL. The index must be kept in step by the use cases: any NEW code path that changes searchable data must call the index (tests cover product save, category save, variants,
attributes, rollback). `reindex` is the safety net. Writes cost one extra `UPDATE` per product save. Typo tolerance and synonyms are not provided.
