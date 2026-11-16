-- Product images remember WHICH stored object they came from, so removing an image can delete exactly that object (and nothing else).
-- Null for images that point at an external URL (no object of ours behind them). An object key belongs to at most one image.
alter table product_images add column object_key varchar(300);
create unique index product_images_object_key_uq on product_images (object_key) where object_key is not null;
