package com.anadepot.testsupport;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.AttributeType;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.ProductVariant;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.catalog.model.WholesaleTier;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builders for tests. Amounts here are test values, not real prices. */
public final class TestData {

	public static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");

	private TestData() {
	}

	public static Money htg(String amount) {
		return Money.of(amount, SupportedCurrency.HTG);
	}

	public static Money usd(String amount) {
		return Money.of(amount, SupportedCurrency.USD);
	}

	public static WholesaleTier tier(int minQuantity, Money unitPrice) {
		return new WholesaleTier(minQuantity, unitPrice);
	}

	/** A price in one currency; wholesale is enabled exactly when tiers are given. */
	public static ProductPrice price(Money retail, Money sale, int minOrder, WholesaleTier... tiers) {
		return new ProductPrice(retail.currency(), retail, sale, minOrder, tiers.length > 0, List.of(tiers), true);
	}

	public static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** Category with a variant-axis "size" (S/M/L) and a free-text "material". */
	public record CategoryFixture(Category category, UUID sizeAttribute, UUID materialAttribute, UUID sizeS, UUID sizeM, UUID sizeL) {
	}

	public static CategoryFixture category() {
		var sizeId = UUID.randomUUID();
		var s = UUID.randomUUID();
		var m = UUID.randomUUID();
		var l = UUID.randomUUID();
		var size = new AttributeDefinition(sizeId, "size", LocalizedText.of("fr", "Taille"), AttributeType.CHOICE, null, false, true, true, 1, true, List.of(
				new AttributeOption(s, "s", LocalizedText.of("fr", "S"), 1, true),
				new AttributeOption(m, "m", LocalizedText.of("fr", "M"), 2, true),
				new AttributeOption(l, "l", LocalizedText.of("fr", "L"), 3, true)));
		var materialId = UUID.randomUUID();
		var material = new AttributeDefinition(materialId, "material", LocalizedText.of("fr", "Matière"), AttributeType.TEXT, null, false, false, false, 2, true, List.of());
		var category = new Category(UUID.randomUUID(), unique("cat"), new LocalizedText(Map.of("fr", "Catégorie test", "ht", "Kategori tès")), LocalizedText.empty(), null, true, 0, List.of(size, material));
		return new CategoryFixture(category, sizeId, materialId, s, m, l);
	}

	public static Product product(UUID categoryId, ProductStatus status, ProductPrice... prices) {
		return product(categoryId, unique("SKU"), status, List.of(), prices);
	}

	public static Product product(UUID categoryId, String sku, ProductStatus status, List<ProductVariant> variants, ProductPrice... prices) {
		return new Product(UUID.randomUUID(), sku, unique("slug"), categoryId, new LocalizedText(Map.of("fr", "Matelas d'essai", "ht", "Matla tès")), LocalizedText.empty(),
				null, SellingUnit.UNIT, LocalizedText.empty(), status, ProductPricing.of(List.of(prices)), variants, List.of(), List.of(), T0, T0);
	}

	public static ProductVariant variant(String sku, Map<UUID, UUID> axis, Map<SupportedCurrency, Money> overrides) {
		return new ProductVariant(UUID.randomUUID(), sku, true, 0, axis, overrides);
	}

	/** Clearly fake pickup configuration used by tests (never a real address). */
	public static com.anadepot.core.settings.model.PickupSettings testPickup(boolean enabled) {
		var schedule = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(d -> new com.anadepot.core.settings.model.DaySchedule(d, true, "09:00", "17:00")).toList();
		var location = new com.anadepot.core.settings.model.PickupLocation("Test Store", "0 Test Street", "Test City", "Test Region", "HT", null, "+509 0000 0000", "test@example.test",
				LocalizedText.of("fr", "Adresse de test"), schedule, LocalizedText.empty());
		var instructions = new com.anadepot.core.settings.model.PickupInstructions(new LocalizedText(Map.of("fr", "Instructions de test", "ht", "Enstriksyon tès")), null, null, null, null);
		return new com.anadepot.core.settings.model.PickupSettings(enabled, LocalizedText.of("fr", "Retrait indisponible (test)"), location, instructions, null, null, null, null);
	}
}
