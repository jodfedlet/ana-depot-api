package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
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

	record VariantResponse(UUID id, String sku, Map<UUID, UUID> axisValues, List<VariantPriceResponse> retailPrices) {
	}

	record ImageResponse(UUID id, String url, Map<String, String> alt, int position) {
	}

	record AttributeValueResponse(UUID attributeId, UUID optionId, String text, String number) {
	}

	record ProductResponse(UUID id, String sku, String slug, UUID categoryId, Map<String, String> name, Map<String, String> description, String brand,
			String sellingUnit, Map<String, String> unitNote, List<PriceResponse> prices, List<VariantResponse> variants, List<ImageResponse> images,
			List<AttributeValueResponse> attributes) {

		/** Public view: only ACTIVE price configurations and variants are exposed. */
		static ProductResponse of(Product p) {
			var prices = p.pricing().all().stream().filter(ProductPrice::active).map(ProductResponse::price).toList();
			var variants = p.variants().stream().filter(v -> v.active()).map(v -> new VariantResponse(v.id(), v.sku(), v.axisValues(),
					v.retailOverrides().entrySet().stream().map(o -> new VariantPriceResponse(o.getKey().name(), o.getValue().amount().toPlainString())).toList())).toList();
			return new ProductResponse(p.id(), p.sku(), p.slug(), p.categoryId(), p.name().values(), p.description().values(), p.brand(), p.unit().name(),
					p.unitNote().values(), prices, variants,
					p.images().stream().map(i -> new ImageResponse(i.id(), i.url(), i.alt().values(), i.position())).toList(),
					p.attributes().stream().map(a -> new AttributeValueResponse(a.attributeId(), a.optionId(), a.text(), a.number() == null ? null : a.number().toPlainString())).toList());
		}

		private static PriceResponse price(ProductPrice x) {
			return new PriceResponse(x.currency().name(), x.retail().amount().toPlainString(), x.sale() == null ? null : x.sale().amount().toPlainString(),
					x.minOrderQuantity(), x.wholesaleEnabled(), x.tiers().stream().map(t -> new TierResponse(t.minQuantity(), t.unitPrice().amount().toPlainString())).toList());
		}
	}

	record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
	}
}
