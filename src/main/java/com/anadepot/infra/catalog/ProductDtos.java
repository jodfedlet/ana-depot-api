package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.StockView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public catalog response shapes. Separate from JPA entities and from the domain objects. Money is a decimal STRING
 * ("25000.00") so no client ever handles it as a binary float; every price carries its currency.
 */
final class ProductDtos {

	private ProductDtos() {
	}

	record TierResponse(int minQuantity, String unitPrice) {
	}

	record PriceResponse(String currency, String retailPrice, String salePrice, int minOrderQuantity, boolean wholesaleEnabled, List<TierResponse> wholesaleTiers) {
	}

	record VariantPriceResponse(String currency, String retailPrice) {
	}

	/** {@code availability}: IN_STOCK | LOW_STOCK (only when the seller set a threshold) | OUT_OF_STOCK | UNTRACKED_AVAILABLE. {@code availableQuantity}: null when stock is not tracked. */
	record VariantResponse(UUID id, String sku, Map<UUID, UUID> axisValues, List<VariantPriceResponse> retailPrices, String availability, Integer availableQuantity) {
	}

	record ImageResponse(UUID id, String url, Map<String, String> alt, int position) {
	}

	record AttributeValueResponse(UUID attributeId, UUID optionId, String text, String number) {
	}

	record ProductResponse(UUID id, String sku, String slug, UUID categoryId, String categorySlug, Map<String, String> name, Map<String, String> description, String brand,
			String sellingUnit, Map<String, String> unitNote, List<PriceResponse> prices, List<VariantResponse> variants, List<ImageResponse> images,
			List<AttributeValueResponse> attributes, String availability, Integer availableQuantity, java.time.Instant createdAt) {

		/**
		 * Public view: only ACTIVE price configurations and variants. Availability comes from the SHARED stock position(s)
		 * (available = onHand - reserved); on-hand and reserved are never exposed, and no low-stock policy is invented.
		 */
		static ProductResponse of(Product p, String categorySlug, List<InventoryItem> stock) {
			var prices = p.pricing().all().stream().filter(ProductPrice::active).map(ProductResponse::price).toList();
			var byVariant = new java.util.HashMap<UUID, InventoryItem>();
			InventoryItem productLevel = null;
			for (var item : stock) {
				if (item.variantId() == null) {
					productLevel = item;
				} else {
					byVariant.put(item.variantId(), item);
				}
			}
			var variantViews = new java.util.ArrayList<StockView>();
			var variants = new java.util.ArrayList<VariantResponse>();
			for (var v : p.variants()) {
				if (!v.active()) {
					continue;
				}
				var view = StockView.of(byVariant.get(v.id()));
				variantViews.add(view);
				variants.add(new VariantResponse(v.id(), v.sku(), v.axisValues(),
						v.retailOverrides().entrySet().stream().map(o -> new VariantPriceResponse(o.getKey().name(), o.getValue().amount().toPlainString())).toList(),
						view.availability().name(), view.availableQuantity()));
			}
			var overall = p.hasVariants() ? StockView.combine(variantViews) : StockView.of(productLevel);
			return new ProductResponse(p.id(), p.sku(), p.slug(), p.categoryId(), categorySlug, p.name().values(), p.description().values(), p.brand(), p.unit().name(),
					p.unitNote().values(), prices, variants,
					p.images().stream().map(i -> new ImageResponse(i.id(), i.url(), i.alt().values(), i.position())).toList(),
					p.attributes().stream().map(a -> new AttributeValueResponse(a.attributeId(), a.optionId(), a.text(), a.number() == null ? null : a.number().toPlainString())).toList(),
					overall.availability().name(), overall.availableQuantity(), p.createdAt());
		}

		private static PriceResponse price(ProductPrice x) {
			return new PriceResponse(x.currency().name(), x.retail().amount().toPlainString(), x.sale() == null ? null : x.sale().amount().toPlainString(),
					x.minOrderQuantity(), x.wholesaleEnabled(), x.tiers().stream().map(t -> new TierResponse(t.minQuantity(), t.unitPrice().amount().toPlainString())).toList());
		}
	}

	record OptionResponse(UUID id, String code, Map<String, String> label, int displayOrder) {
	}

	record AttributeResponse(UUID id, String key, Map<String, String> label, String type, String unit, boolean required, boolean filterable, boolean variantAxis,
			int displayOrder, List<OptionResponse> options) {
	}

	record CategoryResponse(UUID id, String slug, Map<String, String> name, Map<String, String> description, String imageUrl, int displayOrder, long productCount,
			List<AttributeResponse> attributes) {

		static CategoryResponse of(com.anadepot.core.catalog.model.Category c, long productCount, boolean withAttributes) {
			var attributes = !withAttributes ? List.<AttributeResponse>of() : c.attributes().stream().filter(a -> a.active()).map(a -> new AttributeResponse(a.id(), a.key(), a.label().values(),
					a.type().name(), a.unit(), a.required(), a.filterable(), a.variantAxis(), a.displayOrder(),
					a.options().stream().filter(o -> o.active()).map(o -> new OptionResponse(o.id(), o.code(), o.label().values(), o.displayOrder())).toList())).toList();
			return new CategoryResponse(c.id(), c.slug(), c.name().values(), c.description().values(), c.imageUrl(), c.displayOrder(), productCount, attributes);
		}
	}

	record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
	}
}
