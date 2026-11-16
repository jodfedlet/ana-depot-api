-- Part 8: PostgreSQL full-text search for the public catalog (replaces the LIKE-based search_text of V7).
-- search_vector is a tsvector built with the 'simple' configuration over application-normalised (accent-folded, lower-case, letters/digits only) text,
-- so it works for French, Haitian Creole and mixed text with no extension and no dictionary. Weights: A names, B SKU/variant SKUs/brand/category name,
-- C descriptions, D attribute option labels/text values. It is maintained by the catalog use cases in the transaction of the change (docs/adr/0015);
-- the statement below is only a SAFE BACKFILL for existing rows (no accent folding is possible without an extension: run POST /api/v1/admin/catalog/search/reindex
-- after deploying to rebuild every vector exactly). No content is invented.
alter table products add column search_vector tsvector not null default ''::tsvector;

update products p set search_vector =
      setweight(to_tsvector('simple', lower(coalesce((select string_agg(value, ' ') from jsonb_each_text(p.name)), ''))), 'A')
   || setweight(to_tsvector('simple', lower(coalesce(p.sku, '') || ' ' || coalesce(p.brand, '') || ' '
          || coalesce((select string_agg(c_name.value, ' ') from categories c, jsonb_each_text(c.name) c_name where c.id = p.category_id), '') || ' '
          || coalesce((select string_agg(v.sku, ' ') from product_variants v where v.product_id = p.id), ''))), 'B')
   || setweight(to_tsvector('simple', lower(coalesce((select string_agg(value, ' ') from jsonb_each_text(p.description)), ''))), 'C');

-- fastupdate = off: a catalog is read far more than written, and the GIN "pending list" of fast updates makes index-scan cost (and latency) unpredictable
-- after bursts of writes. Each product save pays a slightly dearer index update instead (docs/adr/0015).
create index products_search_vector_idx on products using gin (search_vector) with (fastupdate = off);
-- the LIKE-only column is superseded
alter table products drop column search_text;
