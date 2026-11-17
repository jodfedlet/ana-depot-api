package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What staff submit to create or replace a product. Pure input: nothing is trusted. Amounts are decimal TEXT (never floating point). {@link #toProduct}
 * reports EVERY structural problem at once as {@code {field, code}} issues and builds the domain {@link Product} (whose own rules, price ordering and so
 * on, are then enforced by the domain with stable codes). Images are NOT part of the draft: they have their own endpoints and are kept as they are.
 * Variant ids are kept when they belong to the product being edited (their stock and reservations point at them).
 */
public record ProductDraft(String sku, String slug, UUID categoryId, Map<String, String> name, Map<String, String> description, String brand, String unit,
		Map<String, String> unitNote, String status, List<PriceDraft> prices, List<VariantDraft> variants, List<AttributeValueDraft> attributes) {

	public record PriceDraft(String currency, String retail, String sale, Integer minOrderQuantity, Boolean wholesaleEnabled, List<TierDraft> tiers, Boolean active) {
	}

	public record TierDraft(Integer minQuantity, String unitPrice) {
	}

	public record VariantDraft(UUID id, String sku, Boolean active, Integer position, Map<UUID, UUID> axisValues, Map<String, String> retailOverrides) {
	}

	public record AttributeValueDraft(UUID attributeId, UUID optionId, String text, String number) {
	}

	private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
	static final int MAX_VARIANTS = 200;
	static final int MAX_TIERS = 20;
	static final int MAX_ATTRIBUTES = 100;

	/** The slug to use: the given one, or one derived from the name (first fr, then ht, then any language). Null when there is nothing to derive from. */
	public String slugOrDerived() {
		if (slug != null && !slug.isBlank()) {
			return slug.strip();
		}
		if (name == null) {
			return null;
		}
		for (var lang : List.of("fr", "ht")) {
			var s = slugify(name.get(lang));
			if (!s.isEmpty()) {
				return s;
			}
		}
		return name.values().stream().map(ProductDraft::slugify).filter(s -> !s.isEmpty()).findFirst().orElse(null);
	}

	public static String slugify(String text) {
		if (text == null) {
			return "";
		}
		var folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return folded.length() > 100 ? folded.substring(0, 100).replaceAll("-+$", "") : folded;
	}

	/**
	 * Validates and builds the product. {@code existing} is the product being replaced (null on creation): it supplies the images and creation time.
	 * @param resolvedSlug the slug to store (already made unique by the caller)
	 */
	public Product toProduct(UUID id, Product existing, String resolvedSlug, ProductStatus statusToUse, Instant now) {
		var issues = new ArrayList<FieldIssue>();
		if (categoryId == null) {
			issues.add(new FieldIssue("categoryId", "required"));
		}
		if (sku != null && !sku.isBlank() && !SKU.matcher(sku.strip()).matches()) {
			issues.add(new FieldIssue("sku", "invalid"));
		}
		if (resolvedSlug == null || resolvedSlug.isBlank()) {
			issues.add(new FieldIssue("slug", "required"));
		} else if (resolvedSlug.length() > 120) {
			issues.add(new FieldIssue("slug", "too_long"));
		} else if (!SLUG.matcher(resolvedSlug).matches()) {
			issues.add(new FieldIssue("slug", "invalid"));
		}
		var names = CategoryDraft.texts("name", name, 200, true, issues);
		var descriptions = CategoryDraft.texts("description", description, 5000, false, issues);
		var unitNotes = CategoryDraft.texts("unitNote", unitNote, 200, false, issues);
		if (brand != null && brand.strip().length() > 100) {
			issues.add(new FieldIssue("brand", "too_long"));
		}
		SellingUnit sellingUnit = SellingUnit.UNIT;
		if (unit != null && !unit.isBlank()) {
			try {
				sellingUnit = SellingUnit.valueOf(unit.strip().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				issues.add(new FieldIssue("unit", "invalid"));
			}
		}
		var priceList = prices(issues);
		var variantList = variants(issues, existing, priceList);
		var attributeList = attributes(issues);
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return new Product(id, sku == null || sku.isBlank() ? null : sku.strip(), resolvedSlug, categoryId, names, descriptions, brand == null || brand.isBlank() ? null : brand.strip(), sellingUnit,
				unitNotes, statusToUse, ProductPricing.of(priceList), variantList, attributeList, existing == null ? List.of() : existing.images(), existing == null ? now : existing.createdAt(), now);
	}

	private List<ProductPrice> prices(List<FieldIssue> issues) {
		var out = new ArrayList<ProductPrice>();
		var list = prices == null ? List.<PriceDraft>of() : prices;
		var seen = new HashSet<SupportedCurrency>();
		for (int i = 0; i < list.size(); i++) {
			var p = list.get(i);
			var at = "prices[" + i + "]";
			if (p == null) {
				issues.add(new FieldIssue(at, "required"));
				continue;
			}
			SupportedCurrency currency = currency(p.currency(), at + ".currency", issues);
			if (currency == null) {
				continue;
			}
			if (!seen.add(currency)) {
				issues.add(new FieldIssue(at + ".currency", "duplicate"));
				continue;
			}
			int before = issues.size();
			var retail = money(p.retail(), currency, at + ".retail", true, issues);
			var sale = money(p.sale(), currency, at + ".sale", false, issues);
			var tiers = new ArrayList<WholesaleTier>();
			var tierDrafts = p.tiers() == null ? List.<TierDraft>of() : p.tiers();
			if (tierDrafts.size() > MAX_TIERS) {
				issues.add(new FieldIssue(at + ".tiers", "too_many"));
			} else {
				for (int j = 0; j < tierDrafts.size(); j++) {
					var t = tierDrafts.get(j);
					if (t == null || t.minQuantity() == null) {
						issues.add(new FieldIssue(at + ".tiers[" + j + "].minQuantity", "required"));
						continue;
					}
					var price = money(t.unitPrice(), currency, at + ".tiers[" + j + "].unitPrice", true, issues);
					if (price != null) {
						tiers.add(new WholesaleTier(t.minQuantity(), price));
					}
				}
			}
			if (p.minOrderQuantity() != null && (p.minOrderQuantity() < 1 || p.minOrderQuantity() > 100_000)) {
				issues.add(new FieldIssue(at + ".minOrderQuantity", "invalid"));
			}
			if (issues.size() == before && retail != null) {
				boolean wholesale = p.wholesaleEnabled() != null ? p.wholesaleEnabled() : !tiers.isEmpty();
				out.add(new ProductPrice(currency, retail, sale, p.minOrderQuantity() == null ? 1 : p.minOrderQuantity(), wholesale, tiers, p.active() == null || p.active()));
			}
		}
		return out;
	}

	private List<ProductVariant> variants(List<FieldIssue> issues, Product existing, List<ProductPrice> priceList) {
		var out = new ArrayList<ProductVariant>();
		var list = variants == null ? List.<VariantDraft>of() : variants;
		if (list.size() > MAX_VARIANTS) {
			issues.add(new FieldIssue("variants", "too_many"));
			return out;
		}
		var knownIds = new HashSet<UUID>();
		if (existing != null) {
			existing.variants().forEach(v -> knownIds.add(v.id()));
		}
		var ids = new HashSet<UUID>();
		var skus = new HashSet<String>();
		var combinations = new HashSet<Map<UUID, UUID>>();
		var priced = new HashSet<SupportedCurrency>();
		priceList.forEach(p -> priced.add(p.currency()));
		for (int i = 0; i < list.size(); i++) {
			var v = list.get(i);
			var at = "variants[" + i + "]";
			if (v == null) {
				issues.add(new FieldIssue(at, "required"));
				continue;
			}
			int before = issues.size();
			UUID id = v.id();
			if (id != null) {
				if (!knownIds.contains(id)) {
					issues.add(new FieldIssue(at + ".id", "unknown"));
				} else if (!ids.add(id)) {
					issues.add(new FieldIssue(at + ".id", "duplicate"));
				}
			} else {
				id = UUID.randomUUID();
			}
			if (v.sku() == null || v.sku().isBlank()) {
				issues.add(new FieldIssue(at + ".sku", "required"));
			} else if (!SKU.matcher(v.sku().strip()).matches()) {
				issues.add(new FieldIssue(at + ".sku", "invalid"));
			} else if (!skus.add(v.sku().strip())) {
				issues.add(new FieldIssue(at + ".sku", "duplicate"));
			}
			var axis = v.axisValues() == null ? Map.<UUID, UUID>of() : v.axisValues();
			if (axis.isEmpty()) {
				issues.add(new FieldIssue(at + ".axisValues", "required"));
			} else if (axis.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
				issues.add(new FieldIssue(at + ".axisValues", "invalid"));
			} else if (!combinations.add(new HashMap<>(axis))) {
				issues.add(new FieldIssue(at + ".axisValues", "duplicate"));
			}
			var overrides = new EnumMap<SupportedCurrency, Money>(SupportedCurrency.class);
			if (v.retailOverrides() != null) {
				for (var e : v.retailOverrides().entrySet()) {
					var currency = currency(e.getKey(), at + ".retailOverrides", issues);
					if (currency == null) {
						continue;
					}
					if (!priced.contains(currency)) {
						issues.add(new FieldIssue(at + ".retailOverrides." + currency, "currency_without_price"));
						continue;
					}
					var amount = money(e.getValue(), currency, at + ".retailOverrides." + currency, true, issues);
					if (amount != null) {
						overrides.put(currency, amount);
					}
				}
			}
			if (v.position() != null && (v.position() < 0 || v.position() > 100_000)) {
				issues.add(new FieldIssue(at + ".position", "invalid"));
			}
			if (issues.size() == before) {
				out.add(new ProductVariant(id, v.sku().strip(), v.active() == null || v.active(), v.position() == null ? i : v.position(), axis, overrides));
			}
		}
		return out;
	}

	private List<AttributeValue> attributes(List<FieldIssue> issues) {
		var out = new ArrayList<AttributeValue>();
		var list = attributes == null ? List.<AttributeValueDraft>of() : attributes;
		if (list.size() > MAX_ATTRIBUTES) {
			issues.add(new FieldIssue("attributes", "too_many"));
			return out;
		}
		for (int i = 0; i < list.size(); i++) {
			var a = list.get(i);
			var at = "attributes[" + i + "]";
			if (a == null || a.attributeId() == null) {
				issues.add(new FieldIssue(at + ".attributeId", "required"));
				continue;
			}
			int given = (a.optionId() != null ? 1 : 0) + (a.text() != null ? 1 : 0) + (a.number() != null ? 1 : 0);
			if (given != 1) {
				issues.add(new FieldIssue(at, given == 0 ? "value_required" : "one_value_only"));
				continue;
			}
			if (a.text() != null && (a.text().isBlank() || a.text().length() > 500)) {
				issues.add(new FieldIssue(at + ".text", a.text().isBlank() ? "required" : "too_long"));
				continue;
			}
			if (a.number() != null) {
				var number = decimal(a.number());
				if (number == null) {
					issues.add(new FieldIssue(at + ".number", "invalid"));
					continue;
				}
				out.add(AttributeValue.number(a.attributeId(), number));
			} else if (a.optionId() != null) {
				out.add(AttributeValue.choice(a.attributeId(), a.optionId()));
			} else {
				out.add(AttributeValue.text(a.attributeId(), a.text().strip()));
			}
		}
		return out;
	}

	private static SupportedCurrency currency(String code, String field, List<FieldIssue> issues) {
		if (code == null || code.isBlank()) {
			issues.add(new FieldIssue(field, "required"));
			return null;
		}
		try {
			return SupportedCurrency.valueOf(code.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			issues.add(new FieldIssue(field, "unsupported_currency"));
			return null;
		}
	}

	private static Money money(String text, SupportedCurrency currency, String field, boolean required, List<FieldIssue> issues) {
		if (text == null || text.isBlank()) {
			if (required) {
				issues.add(new FieldIssue(field, "required"));
			}
			return null;
		}
		var amount = decimal(text);
		if (amount == null || amount.signum() <= 0) {
			issues.add(new FieldIssue(field, "invalid"));
			return null;
		}
		try {
			return Money.of(amount, currency);
		} catch (DomainRuleViolation e) {
			issues.add(new FieldIssue(field, "too_many_decimals"));
			return null;
		}
	}

	private static BigDecimal decimal(String text) {
		try {
			var value = new BigDecimal(text.strip());
			return value.precision() - value.scale() > 15 ? null : value;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
