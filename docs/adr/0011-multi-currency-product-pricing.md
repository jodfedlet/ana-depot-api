# ADR 0011 — One product, independent prices per currency
Status: accepted (confirmed business requirement; implemented and tested end to end).

**Context.** Ana Dépôt sells in HTG and USD from launch. One mattress is one product with one category, one attribute set and one stock position — but 25 000 HTG or 180 USD, with different wholesale tiers.
**Decision.** The product has **no currency**. `product_prices(product_id, currency, retail, sale, min_order, wholesale_enabled, active)` — at most one row per currency — with `wholesale_price_tiers` attached by the composite key `(product_id, currency)`; variants may override the retail price per currency (`variant_prices`, composite FKs guarantee the product is priced in that currency).
No price is generated or converted when a currency is missing (requesting it is `price_not_available`). Stock is `inventory_items`, **not** per currency. Reservation lines snapshot the currency and applied price; totals/balances/payments are per currency (ADR 0001).
**Variant semantics** are the storefront's (documented in domain.md): own price ⇒ ignore product sale, scale tiers proportionally, round HALF_UP once, never above base.
**Alternatives.** Duplicate product per currency — rejected (breaks one-identity/one-stock). Price table without composite keys — rejected (loses database-enforced integrity). Global exchange rate — rejected (explicitly out of scope; conversion needs an owner-defined policy kept separate from original prices).
**Consequences.** The storefront's current model (a currency per product) must be migrated to per-currency prices when it is connected. The `currencies` table + enum make adding a currency a small, explicit change.
