package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.AttributeValue;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductImage;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.catalog.model.ProductVariant;
import com.anadepot.core.catalog.model.WholesaleTier;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Entity <-> domain translation for the catalog aggregates. Entities never leave this package. */
final class CatalogMapper {

	private CatalogMapper() {
	}

	// ---------------- entity -> domain ----------------

	static Category toDomain(CategoryEntity e) {
		var attributes = e.attributes.stream().map(a -> new AttributeDefinition(a.id, a.attrKey, new LocalizedText(a.label), a.attrType, a.unit, a.required,
				a.filterable, a.variantAxis, a.displayOrder, a.active,
				a.options.stream().map(o -> new AttributeOption(o.id, o.code, new LocalizedText(o.label), o.displayOrder, o.active)).toList())).toList();
		return new Category(e.id, e.slug, new LocalizedText(e.name), new LocalizedText(e.description), e.imageUrl, e.active, e.displayOrder, attributes);
	}

	static Product toDomain(ProductEntity e) {
		var prices = new ArrayList<ProductPrice>();
		for (var p : e.prices) {
			var tiers = e.tiers.stream().filter(t -> t.currency == p.currency)
					.map(t -> new WholesaleTier(t.minQuantity, Money.of(t.unitPrice, t.currency))).toList();
			prices.add(new ProductPrice(p.currency, Money.of(p.retailPrice, p.currency), p.salePrice == null ? null : Money.of(p.salePrice, p.currency),
					p.minOrderQuantity, p.wholesaleEnabled, tiers, p.active));
		}
		var variants = e.variants.stream().map(v -> {
			Map<UUID, UUID> axis = new HashMap<>();
			v.axisValues.forEach(a -> axis.put(a.attributeId, a.optionId));
			Map<SupportedCurrency, Money> overrides = new EnumMap<>(SupportedCurrency.class);
			v.prices.forEach(p -> overrides.put(p.currency, Money.of(p.retailPrice, p.currency)));
			return new ProductVariant(v.id, v.sku, v.active, v.position, axis, overrides);
		}).toList();
		var attributes = e.attributeValues.stream().map(a -> new AttributeValue(a.attributeId, a.optionId, a.textValue, a.numberValue)).toList();
		var images = e.images.stream().map(i -> new ProductImage(i.id, i.url, new LocalizedText(i.alt), i.position)).toList();
		return new Product(e.id, e.sku, e.slug, e.categoryId, new LocalizedText(e.name), new LocalizedText(e.description), e.brand, e.sellingUnit,
				new LocalizedText(e.unitNote), e.status, ProductPricing.of(prices), variants, attributes, images, e.createdAt, e.updatedAt);
	}

	// ---------------- domain -> entity (update in place) ----------------

	static Map<String, String> text(LocalizedText t) {
		return new LinkedHashMap<>(t.values());
	}

	static void apply(Category c, CategoryEntity e, java.time.Instant now) {
		e.slug = c.slug();
		e.name = text(c.name());
		e.description = text(c.description());
		e.imageUrl = c.imageUrl();
		e.active = c.active();
		e.displayOrder = c.displayOrder();
		e.updatedAt = now;
		var existing = new HashMap<UUID, AttributeDefinitionEntity>();
		e.attributes.forEach(a -> existing.put(a.id, a));
		var kept = new ArrayList<AttributeDefinitionEntity>();
		for (var d : c.attributes()) {
			var a = existing.getOrDefault(d.id(), new AttributeDefinitionEntity());
			a.id = d.id();
			a.category = e;
			a.attrKey = d.key();
			a.label = text(d.label());
			a.attrType = d.type();
			a.unit = d.unit();
			a.required = d.required();
			a.filterable = d.filterable();
			a.variantAxis = d.variantAxis();
			a.displayOrder = d.displayOrder();
			a.active = d.active();
			var existingOptions = new HashMap<UUID, AttributeOptionEntity>();
			a.options.forEach(o -> existingOptions.put(o.id, o));
			var keptOptions = new ArrayList<AttributeOptionEntity>();
			for (var od : d.options()) {
				var o = existingOptions.getOrDefault(od.id(), new AttributeOptionEntity());
				o.id = od.id();
				o.attribute = a;
				o.code = od.code();
				o.label = text(od.label());
				o.displayOrder = od.displayOrder();
				o.active = od.active();
				keptOptions.add(o);
			}
			a.options.clear();
			a.options.addAll(keptOptions);
			kept.add(a);
		}
		e.attributes.clear();
		e.attributes.addAll(kept);
	}

	static void apply(Product p, ProductEntity e) {
		e.sku = p.sku();
		e.slug = p.slug();
		e.categoryId = p.categoryId();
		e.name = text(p.name());
		e.description = text(p.description());
		e.brand = p.brand();
		e.sellingUnit = p.unit();
		e.unitNote = text(p.unitNote());
		e.status = p.status();
		e.updatedAt = p.updatedAt();

		// prices: matched by currency and updated in place (the database keeps one row per product+currency)
		var existingPrices = new EnumMap<SupportedCurrency, ProductPriceEntity>(SupportedCurrency.class);
		e.prices.forEach(x -> existingPrices.put(x.currency, x));
		var keptPrices = new ArrayList<ProductPriceEntity>();
		for (var price : p.pricing().all()) {
			var x = existingPrices.get(price.currency());
			if (x == null) {
				x = new ProductPriceEntity();
				x.id = UUID.randomUUID();
				x.product = e;
				x.currency = price.currency();
				x.createdAt = p.updatedAt();
			}
			x.retailPrice = price.retail().amount();
			x.salePrice = price.sale() == null ? null : price.sale().amount();
			x.minOrderQuantity = price.minOrderQuantity();
			x.wholesaleEnabled = price.wholesaleEnabled();
			x.active = price.active();
			x.updatedAt = p.updatedAt();
			keptPrices.add(x);
		}
		e.prices.clear();
		e.prices.addAll(keptPrices);

		// tiers: rebuilt per save (their natural key is deferrable-unique in the database)
		e.tiers.clear();
		for (var price : p.pricing().all()) {
			for (var tier : price.tiers()) {
				var t = new WholesaleTierEntity();
				t.id = UUID.randomUUID();
				t.product = e;
				t.currency = price.currency();
				t.minQuantity = tier.minQuantity();
				t.unitPrice = tier.unitPrice().amount();
				e.tiers.add(t);
			}
		}

		// variants: matched by id
		var existingVariants = new HashMap<UUID, ProductVariantEntity>();
		e.variants.forEach(v -> existingVariants.put(v.id, v));
		var keptVariants = new ArrayList<ProductVariantEntity>();
		for (var v : p.variants()) {
			var x = existingVariants.getOrDefault(v.id(), new ProductVariantEntity());
			x.id = v.id();
			x.product = e;
			x.sku = v.sku();
			x.active = v.active();
			x.position = v.position();
			x.axisValues.clear();
			v.axisValues().forEach((attr, option) -> {
				var a = new VariantAxisValueEntity();
				a.id = UUID.randomUUID();
				a.variant = x;
				a.attributeId = attr;
				a.optionId = option;
				x.axisValues.add(a);
			});
			x.prices.clear();
			v.retailOverrides().forEach((currency, money) -> {
				var vp = new VariantPriceEntity();
				vp.id = UUID.randomUUID();
				vp.variant = x;
				vp.productId = p.id();
				vp.currency = currency;
				vp.retailPrice = money.amount();
				x.prices.add(vp);
			});
			keptVariants.add(x);
		}
		e.variants.clear();
		e.variants.addAll(keptVariants);

		e.attributeValues.clear();
		for (var v : p.attributes()) {
			var a = new ProductAttributeValueEntity();
			a.id = UUID.randomUUID();
			a.product = e;
			a.attributeId = v.attributeId();
			a.optionId = v.optionId();
			a.textValue = v.text();
			a.numberValue = v.number();
			e.attributeValues.add(a);
		}
		e.images.clear();
		for (var i : p.images()) {
			var x = new ProductImageEntity();
			x.id = i.id();
			x.product = e;
			x.url = i.url();
			x.alt = text(i.alt());
			x.position = i.position();
			e.images.add(x);
		}
	}
}
