package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * A product: ONE identity, ONE category, ONE set of attributes, optional variants and a price configuration per
 * currency ({@link ProductPricing}). Stock is NOT part of this aggregate — it lives in the inventory context and is
 * shared by every currency.
 */
public final class Product {

	private final UUID id;
	private final String sku;
	private final String slug;
	private final UUID categoryId;
	private final LocalizedText name;
	private final LocalizedText description;
	private final String brand;
	private final SellingUnit unit;
	private final LocalizedText unitNote;
	private final ProductStatus status;
	private final ProductPricing pricing;
	private final List<ProductVariant> variants;
	private final List<AttributeValue> attributes;
	private final List<ProductImage> images;
	private final Instant createdAt;
	private final Instant updatedAt;

	public Product(UUID id, String sku, String slug, UUID categoryId, LocalizedText name, LocalizedText description,
			String brand, SellingUnit unit, LocalizedText unitNote, ProductStatus status, ProductPricing pricing,
			List<ProductVariant> variants, List<AttributeValue> attributes, List<ProductImage> images,
			Instant createdAt, Instant updatedAt) {
		if (name.isEmpty()) {
			throw new DomainRuleViolation("product_name_required", "A product needs a name in at least one language");
		}
		if (slug == null || slug.isBlank()) {
			throw new DomainRuleViolation("product_slug_required", "A product needs a slug");
		}
		this.id = id;
		this.sku = sku == null || sku.isBlank() ? null : sku.strip();
		this.slug = slug;
		this.categoryId = categoryId;
		this.name = name;
		this.description = description;
		this.brand = brand;
		this.unit = unit;
		this.unitNote = unitNote;
		this.status = status;
		this.pricing = pricing;
		this.variants = List.copyOf(variants);
		this.attributes = List.copyOf(attributes);
		this.images = List.copyOf(images);
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		var skus = new HashSet<String>();
		for (var v : this.variants) {
			if (!skus.add(v.sku()) || v.sku().equals(this.sku)) {
				throw new DomainRuleViolation("duplicate_sku", "SKU used twice inside the product: " + v.sku());
			}
		}
	}

	/** Same product with another status (used by lifecycle operations). */
	public Product withStatus(ProductStatus next, Instant now) {
		return new Product(id, sku, slug, categoryId, name, description, brand, unit, unitNote, next, pricing, variants, attributes, images, createdAt, now);
	}

	/**
	 * What a customer-facing (ACTIVE) product needs: SKU, an active price, at least one active variant when it has
	 * variants, and every attribute the category marks as required.
	 */
	public void requirePublishable(Category category) {
		if (sku == null) {
			throw new DomainRuleViolation("sku_required", "A SKU is required to publish a product");
		}
		if (!pricing.hasActivePrice()) {
			throw new DomainRuleViolation("price_required", "A product needs at least one active price (HTG and/or USD) to be published");
		}
		if (!variants.isEmpty() && variants.stream().noneMatch(ProductVariant::active)) {
			throw new DomainRuleViolation("variants_required", "A product with variants needs at least one active variant");
		}
		for (var def : category.attributes()) {
			if (def.active() && def.required() && attributes.stream().noneMatch(a -> a.attributeId().equals(def.id()))) {
				throw new DomainRuleViolation("attribute_required", "Required attribute missing: " + def.key());
			}
		}
	}

	/**
	 * The price a customer pays per unit of {@code variantId} (or of the product when it has no variants), in
	 * {@code currency}, for {@code quantity} units. Checks the product is sold, is available in that currency, the
	 * variant is valid and the minimum order quantity is respected.
	 */
	public AppliedPrice quote(UUID variantId, SupportedCurrency currency, int quantity) {
		if (status != ProductStatus.ACTIVE) {
			throw new DomainRuleViolation("product_unavailable", "Product is not for sale: " + id);
		}
		var price = pricing.requireActive(currency);
		ProductVariant variant = null;
		if (!variants.isEmpty()) {
			if (variantId == null) {
				throw new DomainRuleViolation("variant_required", "Choose a variant of this product");
			}
			variant = variant(variantId);
			if (variant == null || !variant.active()) {
				throw new DomainRuleViolation("variant_unavailable", "Variant not available: " + variantId);
			}
		} else if (variantId != null) {
			throw new DomainRuleViolation("variant_unavailable", "This product has no variants");
		}
		if (quantity < price.minOrderQuantity()) {
			throw new DomainRuleViolation("below_minimum_order", "Minimum order is " + price.minOrderQuantity() + " for this product in " + currency);
		}
		return price.quote(variant == null ? null : variant.retailOverride(currency), quantity);
	}

	public ProductVariant variant(UUID variantId) {
		return variants.stream().filter(v -> v.id().equals(variantId)).findFirst().orElse(null);
	}

	public boolean hasVariants() {
		return !variants.isEmpty();
	}

	public UUID id() { return id; }
	public String sku() { return sku; }
	public String slug() { return slug; }
	public UUID categoryId() { return categoryId; }
	public LocalizedText name() { return name; }
	public LocalizedText description() { return description; }
	public String brand() { return brand; }
	public SellingUnit unit() { return unit; }
	public LocalizedText unitNote() { return unitNote; }
	public ProductStatus status() { return status; }
	public ProductPricing pricing() { return pricing; }
	public List<ProductVariant> variants() { return variants; }
	public List<AttributeValue> attributes() { return attributes; }
	public List<ProductImage> images() { return images; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
}
