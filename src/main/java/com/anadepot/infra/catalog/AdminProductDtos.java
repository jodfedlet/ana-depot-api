package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductAdminView;
import com.anadepot.core.catalog.model.ProductDraft;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.shared.money.Money;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff-facing product shapes (every status, every price configuration, stock figures). Separate from the public shapes and from the entities. Money is decimal text. */
final class AdminProductDtos {

	private AdminProductDtos() {
	}

	// ---- requests ----

	record TierRequest(Integer minQuantity, String unitPrice) {
	}

	record PriceRequest(String currency, String retail, String sale, Integer minOrderQuantity, Boolean wholesaleEnabled, List<TierRequest> tiers, Boolean active) {
	}

	record VariantRequest(UUID id, String sku, Boolean active, Integer position, Map<UUID, UUID> axisValues, Map<String, String> retailOverrides) {
	}

	record AttributeValueRequest(UUID attributeId, UUID optionId, String text, String number) {
	}

	/** {@code version} is required when replacing (the version you read) and ignored on creation; {@code status} is read on creation only (DRAFT by default). */
	record ProductRequest(Long version, String sku, String slug, UUID categoryId, Map<String, String> name, Map<String, String> description, String brand, String unit,
			Map<String, String> unitNote, String status, List<PriceRequest> prices, List<VariantRequest> variants, List<AttributeValueRequest> attributes) {

		ProductDraft toDraft() {
			return new ProductDraft(sku, slug, categoryId, name, description, brand, unit, unitNote, status,
					prices == null ? null : prices.stream().map(p -> p == null ? null : new ProductDraft.PriceDraft(p.currency(), p.retail(), p.sale(), p.minOrderQuantity(), p.wholesaleEnabled(),
							p.tiers() == null ? null : p.tiers().stream().map(t -> t == null ? null : new ProductDraft.TierDraft(t.minQuantity(), t.unitPrice())).toList(), p.active())).toList(),
					variants == null ? null : variants.stream().map(v -> v == null ? null : new ProductDraft.VariantDraft(v.id(), v.sku(), v.active(), v.position(), v.axisValues(), v.retailOverrides())).toList(),
					attributes == null ? null : attributes.stream().map(a -> a == null ? null : new ProductDraft.AttributeValueDraft(a.attributeId(), a.optionId(), a.text(), a.number())).toList());
		}
	}

	record StatusRequest(String status, String reason) {
	}

	// ---- responses ----

	record TierResponse(int minQuantity, String unitPrice) {
	}

	record PriceResponse(String currency, String retail, String sale, int minOrderQuantity, boolean wholesaleEnabled, List<TierResponse> tiers, boolean active) {
	}

	record VariantResponse(UUID id, String sku, boolean active, int position, Map<UUID, UUID> axisValues, Map<String, String> retailOverrides) {
	}

	record AttributeValueResponse(UUID attributeId, UUID optionId, String text, String number) {
	}

	record ImageResponse(UUID id, String url, Map<String, String> alt, int position) {
	}

	record StockPosition(UUID id, UUID variantId, boolean tracked, Integer lowStockThreshold, int onHand, int reserved, int available) {
	}

	record StockResponse(int onHand, int reserved, int available, boolean hasUntrackedPosition, String state, List<StockPosition> positions) {
	}

	/** One product line of a category's product list (no stock figures). */
	record Line(UUID id, String sku, String slug, Map<String, String> name, String brand, String status, List<String> currencies, int variantCount, int imageCount, Instant updatedAt) {

		static Line of(Product p) {
			return new Line(p.id(), p.sku(), p.slug(), p.name().values(), p.brand(), p.status().name(),
					p.pricing().all().stream().filter(ProductPrice::active).map(x -> x.currency().name()).sorted().toList(), p.variants().size(), p.images().size(), p.updatedAt());
		}
	}

	record Summary(UUID id, String sku, String slug, UUID categoryId, Map<String, String> name, String brand, String unit, String status, List<PriceResponse> prices, int variantCount,
			int imageCount, String primaryImageUrl, StockResponse stock, long version, Instant createdAt, Instant updatedAt) {
	}

	record Detail(UUID id, String sku, String slug, UUID categoryId, Map<String, String> name, Map<String, String> description, String brand, String unit, Map<String, String> unitNote,
			String status, long version, Instant createdAt, Instant updatedAt, List<PriceResponse> prices, List<VariantResponse> variants, List<AttributeValueResponse> attributes,
			List<ImageResponse> images, StockResponse stock) {
	}

	// ---- mapping ----

	static Summary summary(ProductAdminView v) {
		var p = v.product();
		return new Summary(p.id(), p.sku(), p.slug(), p.categoryId(), p.name().values(), p.brand(), p.unit().name(), p.status().name(), prices(p), p.variants().size(), p.images().size(),
				p.images().stream().filter(i -> i.position() == 0).map(i -> i.url()).findFirst().orElse(null), stock(v), v.version(), p.createdAt(), p.updatedAt());
	}

	static Detail detail(ProductAdminView v) {
		var p = v.product();
		return new Detail(p.id(), p.sku(), p.slug(), p.categoryId(), p.name().values(), p.description().values(), p.brand(), p.unit().name(), p.unitNote().values(), p.status().name(), v.version(),
				p.createdAt(), p.updatedAt(), prices(p), p.variants().stream().map(AdminProductDtos::variant).toList(),
				p.attributes().stream().map(a -> new AttributeValueResponse(a.attributeId(), a.optionId(), a.text(), a.number() == null ? null : a.number().toPlainString())).toList(),
				p.images().stream().map(i -> new ImageResponse(i.id(), i.url(), i.alt().values(), i.position())).toList(), stock(v));
	}

	private static List<PriceResponse> prices(Product p) {
		return p.pricing().all().stream().sorted(java.util.Comparator.comparing(x -> x.currency().name())).map(AdminProductDtos::price).toList();
	}

	private static PriceResponse price(ProductPrice x) {
		return new PriceResponse(x.currency().name(), x.retail().amount().toPlainString(), x.sale() == null ? null : x.sale().amount().toPlainString(), x.minOrderQuantity(), x.wholesaleEnabled(),
				x.tiers().stream().map(t -> new TierResponse(t.minQuantity(), t.unitPrice().amount().toPlainString())).toList(), x.active());
	}

	private static VariantResponse variant(com.anadepot.core.catalog.model.ProductVariant v) {
		var overrides = new LinkedHashMap<String, String>();
		v.retailOverrides().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> overrides.put(e.getKey().name(), ((Money) e.getValue()).amount().toPlainString()));
		return new VariantResponse(v.id(), v.sku(), v.active(), v.position(), v.axisValues(), overrides);
	}

	private static StockResponse stock(ProductAdminView v) {
		String state;
		if (v.stock().isEmpty()) {
			state = "NO_STOCK_RECORD";
		} else if (v.anyUntracked() || v.stock().stream().anyMatch(i -> i.tracked() && i.available() > 0)) {
			state = v.stock().stream().anyMatch(i -> i.tracked() && i.lowStockThreshold() != null && i.available() <= i.lowStockThreshold()) ? "LOW_STOCK" : "IN_STOCK";
		} else {
			state = "OUT_OF_STOCK";
		}
		return new StockResponse(v.onHand(), v.reserved(), v.available(), v.anyUntracked(), state,
				v.stock().stream().map(i -> new StockPosition(i.id(), i.variantId(), i.tracked(), i.lowStockThreshold(), i.onHand(), i.reserved(), i.available())).toList());
	}
}
