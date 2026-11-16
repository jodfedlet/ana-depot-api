-- Part 7: public catalog search support.
-- search_text: accent- and case-insensitive concatenation of every language's name/description + brand + SKU (maintained by the application on every save).
-- sort_name:  the product's primary name normalised, for alphabetical sorting. Both default to '' so the migration is safe on existing rows (which the
-- application refreshes the next time they are saved); no values are invented. No extension (unaccent / pg_trgm) is required.
alter table products add column search_text text not null default '';
alter table products add column sort_name text not null default '';
update products set search_text = lower(coalesce(name::text, '') || ' ' || coalesce(description::text, '') || ' ' || coalesce(brand, '') || ' ' || coalesce(sku, '')),
                    sort_name = lower(coalesce(name ->> 'fr', name ->> 'ht', ''));
create index products_status_created_idx on products (status, created_at desc);
create index products_brand_idx on products (brand) where brand is not null;
create index product_prices_currency_idx on product_prices (currency, product_id) where active;
create index product_attribute_values_option_idx on product_attribute_values (attribute_id, option_id);
create index inventory_items_product_idx on inventory_items (product_id);
