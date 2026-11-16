-- Ana Dépôt schema, part 1: currencies and catalog (categories, attributes, products, variants, per-currency prices).
-- Money columns are numeric(19,2): both launch currencies (HTG, USD) have 2 minor digits. A currency with another
-- number of decimals needs a new migration (see docs/database.md).

create table currencies (
    code            varchar(3)  primary key,
    fraction_digits smallint not null check (fraction_digits between 0 and 4),
    active          boolean  not null default true
);
-- Reference data (not derived from any existing record): the currencies confirmed for launch.
insert into currencies (code, fraction_digits) values ('HTG', 2), ('USD', 2);

create table categories (
    id            uuid         primary key,
    slug          varchar(100) not null unique,
    name          jsonb        not null check (jsonb_typeof(name) = 'object' and name <> '{}'::jsonb),
    description   jsonb        not null default '{}'::jsonb check (jsonb_typeof(description) = 'object'),
    image_url     text,
    active        boolean      not null default true,
    display_order integer      not null default 0,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0
);

create table category_attribute_definitions (
    id            uuid        primary key,
    category_id   uuid        not null references categories (id) on delete cascade,
    attr_key      varchar(60) not null,
    label         jsonb       not null check (jsonb_typeof(label) = 'object' and label <> '{}'::jsonb),
    attr_type     varchar(10) not null check (attr_type in ('CHOICE', 'TEXT', 'NUMBER')),
    unit          varchar(20),
    required      boolean     not null default false,
    filterable    boolean     not null default false,
    variant_axis  boolean     not null default false,
    display_order integer     not null default 0,
    active        boolean     not null default true,
    unique (category_id, attr_key),
    -- only choice attributes can filter the storefront or define variants
    check (attr_type = 'CHOICE' or (not filterable and not variant_axis))
);

create table category_attribute_options (
    id            uuid        primary key,
    attribute_id  uuid        not null references category_attribute_definitions (id) on delete cascade,
    code          varchar(60) not null,
    label         jsonb       not null check (jsonb_typeof(label) = 'object' and label <> '{}'::jsonb),
    display_order integer     not null default 0,
    active        boolean     not null default true,
    unique (attribute_id, code),
    unique (attribute_id, id)  -- target of composite foreign keys: an option always belongs to the stated attribute
);

create table products (
    id            uuid         primary key,
    sku           varchar(64)  unique,
    slug          varchar(120) not null unique,
    category_id   uuid         not null references categories (id),
    name          jsonb        not null check (jsonb_typeof(name) = 'object' and name <> '{}'::jsonb),
    description   jsonb        not null default '{}'::jsonb check (jsonb_typeof(description) = 'object'),
    brand         varchar(100),
    selling_unit  varchar(20)  not null,   -- no CHECK: the unit list is an open business decision
    unit_note     jsonb        not null default '{}'::jsonb check (jsonb_typeof(unit_note) = 'object'),
    status        varchar(10)  not null check (status in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0,
    check (status = 'DRAFT' or sku is not null)  -- only drafts may lack a SKU
);
create index products_category_idx on products (category_id);
create index products_status_idx on products (status);

create table product_attribute_values (
    id           uuid    primary key,
    product_id   uuid    not null references products (id) on delete cascade,
    attribute_id uuid    not null references category_attribute_definitions (id),
    option_id    uuid,
    text_value   text,
    number_value numeric,
    foreign key (attribute_id, option_id) references category_attribute_options (attribute_id, id),
    check (num_nonnulls(option_id, text_value, number_value) = 1),
    unique (product_id, attribute_id) deferrable initially deferred
);

create table product_images (
    id         uuid    primary key,
    product_id uuid    not null references products (id) on delete cascade,
    url        text    not null,
    alt        jsonb   not null default '{}'::jsonb check (jsonb_typeof(alt) = 'object'),
    position   integer not null check (position >= 0),   -- 0 is the primary image
    unique (product_id, position) deferrable initially deferred
);

create table product_variants (
    id         uuid        primary key,
    product_id uuid        not null references products (id) on delete cascade,
    sku        varchar(64) not null unique deferrable initially deferred,
    active     boolean     not null default true,
    position   integer     not null default 0,
    unique (id, product_id)  -- target of composite foreign keys: a variant always belongs to the stated product
);

create table product_variant_axis_values (
    id           uuid primary key,
    variant_id   uuid not null references product_variants (id) on delete cascade,
    attribute_id uuid not null,
    option_id    uuid not null,
    foreign key (attribute_id, option_id) references category_attribute_options (attribute_id, id),
    unique (variant_id, attribute_id) deferrable initially deferred
);

-- ONE product, many currencies: at most one price configuration per (product, currency), never converted from another.
create table product_prices (
    id                 uuid          primary key,
    product_id         uuid          not null references products (id) on delete cascade,
    currency           varchar(3)       not null references currencies (code),
    retail_price       numeric(19,2) not null check (retail_price > 0),
    sale_price         numeric(19,2),
    min_order_quantity integer       not null default 1 check (min_order_quantity >= 1),
    wholesale_enabled  boolean       not null default false,
    active             boolean       not null default true,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    unique (product_id, currency),
    check (sale_price is null or (sale_price > 0 and sale_price < retail_price))
);

create table wholesale_price_tiers (
    id           uuid          primary key,
    product_id   uuid          not null,
    currency     varchar(3)       not null,
    min_quantity integer       not null check (min_quantity >= 2),
    unit_price   numeric(19,2) not null check (unit_price > 0),
    -- a tier belongs to a specific product AND currency
    foreign key (product_id, currency) references product_prices (product_id, currency) on delete cascade deferrable initially deferred,
    unique (product_id, currency, min_quantity) deferrable initially deferred
);

-- A variant may override the retail price in a currency, but only in a currency the product is priced in.
create table variant_prices (
    id           uuid          primary key,
    variant_id   uuid          not null,
    product_id   uuid          not null,
    currency     varchar(3)       not null,
    retail_price numeric(19,2) not null check (retail_price > 0),
    foreign key (variant_id, product_id) references product_variants (id, product_id) on delete cascade deferrable initially deferred,
    foreign key (product_id, currency) references product_prices (product_id, currency) on delete cascade deferrable initially deferred,
    unique (variant_id, currency) deferrable initially deferred
);

-- Not enforceable here (cross-row or cross-table): wholesale tier ordering/price rules, an attribute value's type vs its
-- definition, an attribute belonging to the product's category, variant axes being category axes. See docs/database.md.
