package com.anadepot.infra.catalog;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.unique;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.AttributeValue;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.ProductVariant;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.testsupport.TestData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Public catalog over HTTP against PostgreSQL: visibility, availability from shared stock, search, currency-aware filters and sorting. */
class PublicCatalogApiIntegrationTest extends ApiTestSupport {

	private TestData.CategoryFixture fx;

	private Product product(String fr, String ht, String brand, ProductStatus status, List<ProductVariant> variants, List<AttributeValue> attrs, ProductPrice... prices) {
		var p = new Product(UUID.randomUUID(), unique("SKU"), unique("slug"), fx.category().id(), new LocalizedText(Map.of("fr", fr, "ht", ht)), LocalizedText.of("fr", "Description de " + fr),
				brand, SellingUnit.UNIT, LocalizedText.empty(), status, ProductPricing.of(List.of(prices)), variants, attrs, List.of(), TestData.T0.plusSeconds((long) (Math.random() * 100_000)), TestData.T0);
		return catalog.saveProduct(p, staff);
	}

	private Product stocked(Product p, int onHand, Integer lowThreshold) {
		inventory.register(p.id(), null, true, onHand, lowThreshold, null);
		return p;
	}

	private void freshCategory() {
		fx = TestData.category();
		catalog.saveCategory(fx.category());
	}

	private String slug() {
		return fx.category().slug();
	}

	@Test
	void categoriesAreActiveOnlyWithProductCountsAndDetailListsAttributesAndOptions() throws Exception {
		freshCategory();
		stocked(product("Chaise", "Chèz", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1)), 5, null);
		product("Brouillon", "Bouyon", null, ProductStatus.DRAFT, List.of(), List.of(), price(htg("1.00"), null, 1));
		var hidden = TestData.category().category();
		catalog.saveCategory(new Category(hidden.id(), hidden.slug(), hidden.name(), hidden.description(), null, false, 0, hidden.attributes()));

		get("/api/v1/categories", null).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.slug=='" + slug() + "')].productCount").value(1))        // the draft does not count
				.andExpect(jsonPath("$[?(@.slug=='" + hidden.slug() + "')]").isEmpty())
				.andExpect(jsonPath("$[?(@.slug=='" + slug() + "')].attributes.length()").value(0));
		get("/api/v1/categories/" + slug(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.name.fr").value("Catégorie test"))
				.andExpect(jsonPath("$.attributes.length()").value(2))
				.andExpect(jsonPath("$.attributes[?(@.key=='size')].filterable").value(true))
				.andExpect(jsonPath("$.attributes[?(@.key=='size')].variantAxis").value(true))
				.andExpect(jsonPath("$.attributes[?(@.key=='size')].options.length()").value(3))
				.andExpect(jsonPath("$.attributes[?(@.key=='material')].type").value("TEXT"));
		get("/api/v1/categories/" + hidden.slug(), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
		get("/api/v1/categories/does-not-exist", null).andExpect(status().isNotFound());
	}

	@Test
	void productByIdAndSlugWithAvailabilityFromTheSharedStockWithoutOnHandOrReserved() throws Exception {
		freshCategory();
		var p = stocked(product("Matelas", "Matla", "Marque", ProductStatus.ACTIVE, List.of(), List.of(),
				price(htg("25000.00"), null, 1, tier(5, htg("23000.00"))), price(usd("180.00"), null, 1, tier(5, usd("165.00")))), 10, null);
		ensurePickupAvailable();
		submit(key(), line(p, 3, SupportedCurrency.HTG), line(p, 2, SupportedCurrency.USD));              // 5 reserved across both currencies

		var body = get("/api/v1/products/" + p.id(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.availability").value("IN_STOCK"))
				.andExpect(jsonPath("$.availableQuantity").value(5))                       // 10 - 5, shared by HTG and USD
				.andExpect(jsonPath("$.categorySlug").value(slug()))
				.andExpect(jsonPath("$.prices.length()").value(2))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("onHand").doesNotContain("reserved");
		get("/api/v1/products/" + p.slug(), null).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(p.id().toString()));
		get("/api/v1/products/" + UUID.randomUUID(), null).andExpect(status().isNotFound());
		get("/api/v1/products/unknown-slug", null).andExpect(status().isNotFound());
	}

	@Test
	void availabilityStatesOutOfStockUntrackedLowOnlyWithASellerThresholdAndPerVariant() throws Exception {
		freshCategory();
		var out = stocked(product("Epuise", "Fini", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1)), 0, null);
		get("/api/v1/products/" + out.id(), null).andExpect(jsonPath("$.availability").value("OUT_OF_STOCK")).andExpect(jsonPath("$.availableQuantity").value(0));

		var untracked = product("Sur commande", "Sou kòmand", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1));
		inventory.register(untracked.id(), null, false, 0, null, null);
		get("/api/v1/products/" + untracked.id(), null).andExpect(jsonPath("$.availability").value("UNTRACKED_AVAILABLE")).andExpect(jsonPath("$.availableQuantity").doesNotExist());

		var noStockRow = product("Sans stock", "San stòk", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1));
		get("/api/v1/products/" + noStockRow.id(), null).andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"));   // cannot be reserved

		var withThreshold = stocked(product("Presque fini", "Prèske fini", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1)), 4, 5);
		get("/api/v1/products/" + withThreshold.id(), null).andExpect(jsonPath("$.availability").value("LOW_STOCK")).andExpect(jsonPath("$.availableQuantity").value(4));
		var noThreshold = stocked(product("Peu mais ok", "Ti kras", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1)), 1, null);
		get("/api/v1/products/" + noThreshold.id(), null).andExpect(jsonPath("$.availability").value("IN_STOCK"));        // no policy invented

		var v1 = TestData.variant(unique("V"), Map.of(fx.sizeAttribute(), fx.sizeS()), Map.of(SupportedCurrency.HTG, htg("12.00")));
		var v2 = TestData.variant(unique("V"), Map.of(fx.sizeAttribute(), fx.sizeL()), Map.of());
		var variants = product("Chaussure", "Soulye", null, ProductStatus.ACTIVE, List.of(v1, v2), List.of(), price(htg("10.00"), null, 1));
		inventory.register(variants.id(), v1.id(), true, 0, null, null);
		inventory.register(variants.id(), v2.id(), true, 7, null, null);
		get("/api/v1/products/" + variants.id(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.availability").value("IN_STOCK")).andExpect(jsonPath("$.availableQuantity").value(7))
				.andExpect(jsonPath("$.variants[?(@.sku=='" + v1.sku() + "')].availability").value("OUT_OF_STOCK"))
				.andExpect(jsonPath("$.variants[?(@.sku=='" + v2.sku() + "')].availableQuantity").value(7))
				.andExpect(jsonPath("$.variants[?(@.sku=='" + v1.sku() + "')].retailPrices[0].retailPrice").value("12.00"));
	}

	@Test
	void onlyActiveProductsInActiveCategoriesAreVisible_andInactiveCurrencyPricesAreNotExposed() throws Exception {
		freshCategory();
		var active = stocked(product("Visible", "Vizib", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1)), 3, null);
		var inactive = product("Inactif", "Inaktif", null, ProductStatus.INACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1));
		var draft = product("Brouillon", "Bouyon", null, ProductStatus.DRAFT, List.of(), List.of(), price(htg("10.00"), null, 1));
		var paused = new ProductPrice(SupportedCurrency.USD, usd("9.00"), null, 1, false, List.of(), false);
		var withPausedCurrency = stocked(product("Devise en pause", "Lajan poze", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1), paused), 3, null);

		get("/api/v1/products?category=" + slug() + "&size=50", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id=='" + active.id() + "')]").isNotEmpty())
				.andExpect(jsonPath("$.items[?(@.id=='" + inactive.id() + "')]").isEmpty())
				.andExpect(jsonPath("$.items[?(@.id=='" + draft.id() + "')]").isEmpty());
		get("/api/v1/products/" + withPausedCurrency.id(), null).andExpect(jsonPath("$.prices.length()").value(1)).andExpect(jsonPath("$.prices[0].currency").value("HTG"));
		// a currency the product is not sold in is simply absent: never generated
		get("/api/v1/products?category=" + slug() + "&currency=USD", null).andExpect(jsonPath("$.totalItems").value(0));

		var cat = fx.category();
		catalog.saveCategory(new Category(cat.id(), cat.slug(), cat.name(), cat.description(), null, false, 0, cat.attributes()));      // deactivate the category
		get("/api/v1/products/" + active.id(), null).andExpect(status().isNotFound());
		get("/api/v1/products?category=" + slug(), null).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void searchIsAccentAndCaseInsensitiveAcrossLanguagesDescriptionBrandAndSku() throws Exception {
		freshCategory();
		var p = stocked(product("Matelas orthopédique", "Matla ortopedik", "SommeilPlus", ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1)), 3, null);
		product("Table", "Tab", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		for (var q : List.of("matelas", "MATELAS ortho", "orthopedique", "matla", "ortopedik", "sommeilplus", p.sku().toLowerCase(), "description de matelas")) {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/products").param("category", slug()).param("q", q))
					.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(p.id().toString()));
		}
		get("/api/v1/products?category=" + slug() + "&q=inexistant", null).andExpect(jsonPath("$.totalItems").value(0));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/products").param("category", slug()).param("q", "100%_")).andExpect(jsonPath("$.totalItems").value(0));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/products").param("q", "x".repeat(101))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("search_query_too_long"));
	}

	@Test
	void currencyWholesaleAndPriceFiltersNeverCompareAcrossCurrencies() throws Exception {
		freshCategory();
		var htgOnly = stocked(product("HTG seul", "HTG sèl", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("5000.00"), null, 1)), 3, null);
		var usdOnly = stocked(product("USD seul", "USD sèl", null, ProductStatus.ACTIVE, List.of(), List.of(), price(usd("20.00"), null, 1, tier(5, usd("15.00")))), 3, null);
		var both = stocked(product("Les deux", "Toude", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("3000.00"), null, 1, tier(5, htg("2500.00"))), price(usd("40.00"), usd("35.00"), 1)), 3, null);
		String base = "/api/v1/products?category=" + slug() + "&size=50";

		get(base + "&currency=HTG", null).andExpect(jsonPath("$.totalItems").value(2));
		get(base + "&currency=USD", null).andExpect(jsonPath("$.totalItems").value(2));
		get(base + "&currency=usd", null).andExpect(jsonPath("$.totalItems").value(2));                      // case-insensitive code
		get(base + "&currency=EUR", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unsupported_currency"));
		// wholesale is per currency: "both" has wholesale in HTG only, "usdOnly" in USD only
		get(base + "&wholesale=true&currency=HTG", null).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(both.id().toString()));
		get(base + "&wholesale=true&currency=USD", null).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(usdOnly.id().toString()));
		get(base + "&wholesale=true", null).andExpect(jsonPath("$.totalItems").value(2));                    // any currency
		// price ranges are interpreted inside ONE currency, on the effective (sale if any) price
		get(base + "&currency=USD&min=30&max=36", null).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(both.id().toString()));   // 35 sale
		get(base + "&currency=HTG&min=4000", null).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(htgOnly.id().toString()));
		get(base + "&currency=HTG&max=100", null).andExpect(jsonPath("$.totalItems").value(0));           // 20 USD is NOT "below 100 HTG"
		get(base + "&min=10", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("currency_required"));
		get(base + "&sort=price_asc", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("currency_required"));
		get(base + "&currency=HTG&min=9&max=1", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("price_range_invalid"));
		get(base + "&currency=HTG&min=abc", null).andExpect(status().isUnprocessableContent());
	}

	@Test
	void sortsByNewestNameAndPriceWithinTheChosenCurrencyOnly() throws Exception {
		freshCategory();
		var cheap = stocked(product("Bravo", "B", null, ProductStatus.ACTIVE, List.of(), List.of(), price(usd("10.00"), null, 1)), 3, null);
		var middle = stocked(product("Alpha", "A", null, ProductStatus.ACTIVE, List.of(), List.of(), price(usd("50.00"), usd("20.00"), 1), price(htg("100.00"), null, 1)), 3, null);   // effective 20
		var dear = stocked(product("Charlie", "C", null, ProductStatus.ACTIVE, List.of(), List.of(), price(usd("30.00"), null, 1), price(htg("9.00"), null, 1)), 3, null);
		var htgOnly = stocked(product("Delta", "D", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("1.00"), null, 1)), 3, null);
		String base = "/api/v1/products?category=" + slug() + "&size=50";

		get(base + "&sort=name", null).andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(middle.id().toString(), cheap.id().toString(), dear.id().toString(), htgOnly.id().toString())));
		// USD price order: only products priced in USD take part (HTG-only "Delta" is absent, not "cheapest because 1 < 10")
		get(base + "&currency=USD&sort=price_asc", null).andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(cheap.id().toString(), middle.id().toString(), dear.id().toString())));
		get(base + "&currency=USD&sort=price_desc", null).andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(dear.id().toString(), middle.id().toString(), cheap.id().toString())));
		get(base + "&currency=HTG&sort=price_asc", null).andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(htgOnly.id().toString(), dear.id().toString(), middle.id().toString())));
		get(base + "&sort=bogus", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("sort_invalid"));
		get(base, null).andExpect(jsonPath("$.totalItems").value(4));                                      // newest (default) lists everything, mixed currencies, no comparison
	}

	@Test
	void attributeBrandAndInStockFilters() throws Exception {
		freshCategory();
		var small = stocked(product("Petit", "Ti", "Alpha", ProductStatus.ACTIVE, List.of(), List.of(AttributeValue.choice(fx.sizeAttribute(), fx.sizeS())), price(htg("10.00"), null, 1)), 3, null);
		var large = stocked(product("Grand", "Gwo", "Beta", ProductStatus.ACTIVE, List.of(), List.of(AttributeValue.choice(fx.sizeAttribute(), fx.sizeL())), price(htg("10.00"), null, 1)), 0, null);
		var plain = stocked(product("Neutre", "Net", "Alpha", ProductStatus.ACTIVE, List.of(), List.of(AttributeValue.text(fx.materialAttribute(), "bois")), price(htg("10.00"), null, 1)), 2, null);
		String base = "/api/v1/products?category=" + slug() + "&size=50";

		get(base + "&attr=size:s", null).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(small.id().toString()));
		get(base + "&attr=size:s&attr=size:l", null).andExpect(jsonPath("$.totalItems").value(2));          // OR within one attribute
		get(base + "&attr=size:s&attr=material:bois", null).andExpect(jsonPath("$.totalItems").value(0));     // AND across attributes (material is TEXT: no option code matches)
		get(base + "&attr=size:xl", null).andExpect(jsonPath("$.totalItems").value(0));
		get(base + "&attr=bad", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("filter_invalid"));
		get(base + "&brand=alpha", null).andExpect(jsonPath("$.totalItems").value(2));
		get(base + "&brand=Beta&brand=alpha", null).andExpect(jsonPath("$.totalItems").value(3));
		get(base + "&inStock=true", null).andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.items[?(@.id=='" + large.id() + "')]").isEmpty());
		assertThat(plain.id()).isNotNull();
	}

	@Test
	void paginationIsBoundedAndStable() throws Exception {
		freshCategory();
		for (int i = 0; i < 5; i++) {
			stocked(product("Produit " + i, "Pwodwi " + i, null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("10.00"), null, 1)), 1, null);
		}
		String base = "/api/v1/products?category=" + slug() + "&sort=name";
		get(base + "&size=2&page=0", null).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalItems").value(5)).andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items[0].name.fr").value("Produit 0"));
		get(base + "&size=2&page=2", null).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].name.fr").value("Produit 4"));
		get(base + "&size=2&page=9", null).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalItems").value(5));
		get(base + "&size=0", null).andExpect(status().isUnprocessableContent());
		get(base + "&size=101", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_page"));
		get(base + "&page=-1", null).andExpect(status().isUnprocessableContent());
	}

}
