package com.anadepot.infra.catalog;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.unique;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.AttributeType;
import com.anadepot.core.catalog.model.AttributeValue;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import com.anadepot.core.catalog.model.ProductPricing;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.ProductVariant;
import com.anadepot.core.catalog.model.SellingUnit;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.Json;
import com.anadepot.testsupport.TestData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL full-text search over HTTP: ranking, multi-term AND, prefix, accents, French + Haitian Creole, filters, safety and index synchronisation. */
class CatalogFullTextSearchIntegrationTest extends ApiTestSupport {

	@Autowired PlatformTransactionManager tm;

	private Category category;
	private UUID colourAttr;
	private UUID redOption;

	@BeforeEach
	void freshCategory() {
		colourAttr = UUID.randomUUID();
		redOption = UUID.randomUUID();
		var colour = new AttributeDefinition(colourAttr, "couleur", LocalizedText.of("fr", "Couleur"), AttributeType.CHOICE, null, false, true, false, 1, true,
				List.of(new AttributeOption(redOption, "rouge", new LocalizedText(Map.of("fr", "Rouge vif", "ht", "Wouj")), 1, true)));
		var material = new AttributeDefinition(UUID.randomUUID(), "matiere", LocalizedText.of("fr", "Matière"), AttributeType.TEXT, null, false, false, false, 2, true, List.of());
		category = catalog.saveCategory(new Category(UUID.randomUUID(), unique("cat"), new LocalizedText(Map.of("fr", "Mobilier salon", "ht", "Mèb salon")), LocalizedText.empty(), null, true, 0, List.of(colour, material)));
	}

	private Product make(String fr, String ht, String description, String brand, ProductStatus status, List<ProductVariant> variants, List<AttributeValue> attrs, ProductPrice... prices) {
		var p = new Product(UUID.randomUUID(), unique("SKU"), unique("slug"), category.id(), new LocalizedText(ht == null ? Map.of("fr", fr) : Map.of("fr", fr, "ht", ht)),
				description == null ? LocalizedText.empty() : LocalizedText.of("fr", description), brand, SellingUnit.UNIT, LocalizedText.empty(), status,
				ProductPricing.of(List.of(prices)), variants, attrs, List.of(), Instant.now(), Instant.now());
		var saved = catalog.saveProduct(p, staff);
		inventory.register(saved.id(), null, true, 5, null, null);
		return saved;
	}

	private Product simple(String fr, String ht) {
		return make(fr, ht, null, null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
	}

	/** Calls the endpoint with real query PARAMETERS (MockMvc would double-encode a pre-encoded URL string). */
	private org.springframework.test.web.servlet.ResultActions call(String path, String q, String extra) throws Exception {
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
		if (extra == null || !extra.contains("size=")) {
			request.param("size", "50");
		}
		if (q != null) {
			request.param("q", q);
		}
		if (extra != null) {
			for (var pair : extra.split("&")) {
				var kv = pair.split("=", 2);
				request.param(kv[0], kv.length > 1 ? kv[1] : "");
			}
		}
		return mvc.perform(request);
	}

	private List<String> search(String q, String extra) throws Exception {
		var body = call("/api/v1/products/search", q, "category=" + category.slug() + (extra == null ? "" : "&" + extra)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		@SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) Json.readMap(body).get("items");
		var ids = new ArrayList<String>();
		items.forEach(i -> ids.add((String) i.get("id")));
		return ids;
	}

	private List<String> search(String q) throws Exception {
		return search(q, null);
	}

	// ------------------------------------------------------------------ matching

	@Test
	void prefixAccentsAndCaseMatchInBothDirections() throws Exception {
		var p = simple("Matière première", "Matyè");
		for (var q : List.of("matiere", "MATIÈRE", "matière premiere", "matel".equals("x") ? "" : "mati", "premi", "première")) {
			assertThat(search(q)).as(q).containsExactly(p.id().toString());
		}
		var m = simple("Matelas orthopédique", null);
		assertThat(search("matel")).containsExactly(m.id().toString());                           // prefix
		assertThat(search("orthopedique")).containsExactly(m.id().toString());                    // stored accented, query plain
		assertThat(search("orthopédique")).containsExactly(m.id().toString());                    // query accented
		assertThat(search("zzzz")).isEmpty();
	}

	@Test
	void multipleTermsUseAndSemantics() throws Exception {
		var both = simple("Chaise pliante bois", null);
		simple("Chaise fixe", null);
		simple("Table pliante", null);
		assertThat(search("chaise pliante")).containsExactly(both.id().toString());
		assertThat(search("pliante chaise bois")).containsExactly(both.id().toString());          // order does not matter
		assertThat(search("chaise pliante metal")).isEmpty();
		assertThat(search("chaise")).hasSize(2);
	}

	@Test
	void frenchPluralAndSingularFindEachOther() throws Exception {
		var p = simple("Chaise pliante", null);
		assertThat(search("chaises")).containsExactly(p.id().toString());
		assertThat(search("chaise")).containsExactly(p.id().toString());
		var plural = simple("Tables basses", null);
		assertThat(search("table")).containsExactly(plural.id().toString());
		assertThat(search("tables basse")).containsExactly(plural.id().toString());
	}

	@Test
	void haitianCreoleWordsAndMixedLanguageProducts() throws Exception {
		var matla = simple("Matelas mousse", "Matla mous");
		var chez = simple("Chaise empilable", "Chèz anpile");
		var soulye = simple("Chaussure sport", "Soulye espò");
		assertThat(search("matla")).containsExactly(matla.id().toString());
		assertThat(search("chèz")).containsExactly(chez.id().toString());
		assertThat(search("chez")).containsExactly(chez.id().toString());                         // typed without the accent
		assertThat(search("soulye espo")).containsExactly(soulye.id().toString());                // "espò" matched by "espo"
		assertThat(search("espò")).containsExactly(soulye.id().toString());
		// each product is found by ANY of its languages
		assertThat(search("chaussure")).containsExactly(soulye.id().toString());
		assertThat(search("mousse")).containsExactly(matla.id().toString());
		// mixed-language query: French word + Creole word of the same product
		assertThat(search("matelas mous")).containsExactly(matla.id().toString());
		assertThat(search("chaise anpile")).containsExactly(chez.id().toString());
	}

	@Test
	void categoryNameAttributeLabelsTextValuesAndVariantSkusAreSearchable() throws Exception {
		var variant = TestData.variant(unique("VAR").replace("-", ""), Map.of(), Map.of());
		var p = make("Canapé", null, null, null, ProductStatus.ACTIVE, List.of(variant),
				List.of(AttributeValue.choice(colourAttr, redOption), AttributeValue.text(category.attributes().get(1).id(), "velours cotelé")), price(htg("100.00"), null, 1));
		assertThat(search("mobilier")).contains(p.id().toString());                               // category name (fr)
		assertThat(search("meb")).contains(p.id().toString());                                    // category name (ht, "Mèb")
		assertThat(search("wouj")).containsExactly(p.id().toString());                            // attribute option label (ht)
		assertThat(search("rouge vif")).containsExactly(p.id().toString());                       // attribute option label (fr)
		assertThat(search("velours")).containsExactly(p.id().toString());                         // attribute text value
		assertThat(search(variant.sku())).containsExactly(p.id().toString());                     // variant SKU
	}

	@Test
	void brandAndDescriptionAreSearchable() throws Exception {
		var p = make("Objet", null, "Fabriqué artisanalement à Jacmel", "Brandéco", ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		assertThat(search("brandeco")).containsExactly(p.id().toString());
		assertThat(search("jacmel")).containsExactly(p.id().toString());
		assertThat(search("artisanalement")).containsExactly(p.id().toString());
	}

	// ------------------------------------------------------------------ ranking

	@Test
	void nameMatchOutranksDescriptionAndAttributeMatches() throws Exception {
		var inDescription = make("Objet décoratif", null, "Un joli lampadaire moderne", null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		var inAttribute = make("Objet salon", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(AttributeValue.text(category.attributes().get(1).id(), "lampadaire")), price(htg("100.00"), null, 1));
		var inName = make("Lampadaire design", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		assertThat(search("lampadaire")).containsExactly(inName.id().toString(), inDescription.id().toString(), inAttribute.id().toString());
		// "relevance" is the default with a text; an explicit sort overrides it
		assertThat(search("lampadaire", "sort=relevance")).containsExactly(inName.id().toString(), inDescription.id().toString(), inAttribute.id().toString());
		assertThat(search("lampadaire", "sort=name")).hasSize(3);
	}

	@Test
	void anExactSkuMatchIsAlwaysFirst() throws Exception {
		var bySku = make("Objet neutre", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		var byName = simple(bySku.sku() + " dans le nom", null);
		assertThat(search(bySku.sku()).getFirst()).isEqualTo(bySku.id().toString());
		assertThat(search(bySku.sku().toUpperCase()).getFirst()).isEqualTo(bySku.id().toString());
		assertThat(search(bySku.sku())).contains(byName.id().toString());
	}

	@Test
	void rankingIsDeterministicAndPaginationIsStable() throws Exception {
		for (int i = 0; i < 7; i++) {
			simple("Pagination article", null);
		}
		var all = search("pagination");
		assertThat(all).hasSize(7).doesNotHaveDuplicates();
		var pages = new ArrayList<String>();
		for (int page = 0; page < 3; page++) {
			pages.addAll(search("pagination", "size=3&page=" + page).subList(0, Math.min(3, 7 - page * 3)));
		}
		call("/api/v1/products/search", "pagination", "category=" + category.slug() + "&size=3&page=2").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalItems").value(7)).andExpect(jsonPath("$.totalPages").value(3));
		assertThat(search("pagination")).isEqualTo(all);                                          // the same query returns the same order
	}

	// ------------------------------------------------------------------ eligibility and filters

	@Test
	void onlyActiveProductsWithAnActivePriceAreDiscoverable() throws Exception {
		var visible = simple("Eligible gadget", null);
		make("Eligible gadget inactif", null, null, null, ProductStatus.INACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		make("Eligible gadget brouillon", null, null, null, ProductStatus.DRAFT, List.of(), List.of(), price(htg("100.00"), null, 1));
		var pausedOnly = new ProductPrice(com.anadepot.core.shared.money.SupportedCurrency.USD, usd("5.00"), null, 1, false, List.of(), false);
		make("Eligible gadget sans prix actif", null, null, null, ProductStatus.INACTIVE, List.of(), List.of(), pausedOnly);
		assertThat(search("eligible gadget")).containsExactly(visible.id().toString());
		assertThat(search("eligible gadget", "currency=USD")).isEmpty();                           // the visible one has no USD price: never generated
	}

	@Test
	void filtersCombineWithTheTextCurrencyPriceStockAndWholesale() throws Exception {
		var cheapHtg = simple("Combo lampe", null);
		var usdWholesale = make("Combo lampe pro", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(), price(usd("40.00"), null, 1, tier(5, usd("30.00"))));
		var out = make("Combo lampe vide", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(), price(htg("100.00"), null, 1));
		inventory.adjust(out.id(), null, com.anadepot.core.inventory.model.MovementType.RECOUNT, 0, com.anadepot.core.inventory.model.StockReason.COUNT, null, staff.userId(), key());
		assertThat(search("combo lampe")).hasSize(3);
		assertThat(search("combo lampe", "currency=USD")).containsExactly(usdWholesale.id().toString());
		assertThat(search("combo lampe", "currency=USD&minPrice=35")).containsExactly(usdWholesale.id().toString());
		assertThat(search("combo lampe", "currency=USD&maxPrice=35")).isEmpty();
		assertThat(search("combo lampe", "wholesale=true")).containsExactly(usdWholesale.id().toString());
		assertThat(search("combo lampe", "inStock=true")).doesNotContain(out.id().toString()).contains(cheapHtg.id().toString());
		assertThat(search("combo lampe", "currency=HTG&sort=price-asc")).hasSize(2);
		get("/api/v1/products/search?q=lampe&minPrice=1", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("currency_required"));
		assertThat(search("combo lampe", "attr=couleur:rouge")).isEmpty();
	}

	@Test
	void theProductsEndpointWithQIsTheSameSearch() throws Exception {
		var p = simple("Identique recherche", null);
		var viaSearch = call("/api/v1/products/search", "identique", "category=" + category.slug()).andReturn().getResponse().getContentAsString();
		var viaList = call("/api/v1/products", "identique", "category=" + category.slug()).andReturn().getResponse().getContentAsString();
		assertThat(viaList).isEqualTo(viaSearch).contains(p.id().toString()).doesNotContain("\"rank\"");
	}

	// ------------------------------------------------------------------ safety

	@Test
	void emptyWhitespaceAndOperatorOnlyQueriesDoNotFilterByText() throws Exception {
		simple("Liste un", null);
		simple("Liste deux", null);
		assertThat(search(null)).hasSize(2);
		assertThat(search("")).hasSize(2);
		assertThat(search("   ")).hasSize(2);
		assertThat(search("&|!():*'\"\\")).hasSize(2);                                            // nothing searchable remains: same as no text
	}

	@Test
	void tsqueryOperatorsQuotesAndInjectionAttemptsAreNeutralised() throws Exception {
		var p = simple("Operateur test", null);
		var nasty = List.of("operateur & test", "operateur | zzz", "!operateur", "(operateur)", "operateur:*", "operateur'", "\"operateur test\"", "operateur\\", "operateur <-> test",
				"'; drop table products; --", "operateur\u0000", "%_", "\\", "'", "&", "a & b | c !d (e) f:* 'g' \"h\" \\i");
		for (var q : nasty) {
			call("/api/v1/products/search", q, null).andExpect(status().isOk());   // never a 500
		}
		assertThat(search("operateur & test")).containsExactly(p.id().toString());                // operators are just separators
		assertThat(search("operateur | zzz")).isEmpty();                                          // "|" is NOT an OR: AND with "zzz"
		assertThat(search("'; drop table products; --")).isEmpty();
		// control characters in ANY user-supplied search input are neutralised or refused, never a 500
		call("/api/v1/products/search", "ok\u0000x", "category=a\u0000b&brand=x\u0000y").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("filter_invalid"));
		call("/api/v1/products/search", "ok", "attr=k\u0000:v").andExpect(status().isUnprocessableContent());
		call("/api/v1/products/search", "ok", "category=a\u0000b").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
		assertThat(simple("Table toujours la", null)).isNotNull();                                 // the table still exists and works
	}

	@Test
	void overLongOrTooManyTermQueriesAreRejectedAndDeepPagingIsRefused() throws Exception {
		call("/api/v1/products/search", "x".repeat(101), null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("search_query_too_long"));
		call("/api/v1/products/search", "aa bb cc dd ee ff gg hh ii", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("search_query_too_long"));
		get("/api/v1/products/search?q=x&page=500&size=100", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("page_out_of_range"));
		get("/api/v1/products/search?q=x&size=101", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_page"));
	}

	// ------------------------------------------------------------------ index synchronisation

	private Product rename(Product p, String fr, ProductStatus status) {
		return catalog.saveProduct(new Product(p.id(), p.sku(), p.slug(), p.categoryId(), LocalizedText.of("fr", fr), p.description(), p.brand(), p.unit(), p.unitNote(), status, p.pricing(),
				p.variants(), p.attributes(), p.images(), p.createdAt(), Instant.now()), staff);
	}

	@Test
	void renamingAProductUpdatesTheSearchAndItsSkuStaysSearchable() throws Exception {
		var p = simple("Ancien nom unique", null);
		assertThat(search("ancien")).containsExactly(p.id().toString());
		rename(p, "Nouveau libelle", ProductStatus.ACTIVE);
		assertThat(search("ancien")).isEmpty();
		assertThat(search("nouveau libelle")).containsExactly(p.id().toString());
		assertThat(search(p.sku())).containsExactly(p.id().toString());
	}

	@Test
	void deactivatingRemovesAndReactivatingRestoresAProduct() throws Exception {
		var p = simple("Cycle de vie", null);
		assertThat(search("cycle")).containsExactly(p.id().toString());
		rename(p, "Cycle de vie", ProductStatus.INACTIVE);
		assertThat(search("cycle")).isEmpty();
		rename(p, "Cycle de vie", ProductStatus.ACTIVE);
		assertThat(search("cycle")).containsExactly(p.id().toString());
	}

	@Test
	void renamingTheCategoryOrChangingAnOptionLabelRefreshesItsProducts() throws Exception {
		var p = make("Fauteuil", null, null, null, ProductStatus.ACTIVE, List.of(), List.of(AttributeValue.choice(colourAttr, redOption)), price(htg("100.00"), null, 1));
		assertThat(search("salon")).contains(p.id().toString());
		assertThat(search("rouge")).contains(p.id().toString());
		assertThat(search("terrasse")).isEmpty();

		var renamedAttrs = new ArrayList<AttributeDefinition>();
		for (var a : category.attributes()) {
			renamedAttrs.add(a.id().equals(colourAttr)
					? new AttributeDefinition(a.id(), a.key(), a.label(), a.type(), a.unit(), a.required(), a.filterable(), a.variantAxis(), a.displayOrder(), a.active(),
							List.of(new AttributeOption(redOption, "rouge", new LocalizedText(Map.of("fr", "Bordeaux", "ht", "Wouj fonse")), 1, true)))
					: a);
		}
		category = catalog.saveCategory(new Category(category.id(), category.slug(), new LocalizedText(Map.of("fr", "Mobilier terrasse", "ht", "Mèb teras")), category.description(), null, true, 0, renamedAttrs));

		assertThat(search("terrasse")).containsExactly(p.id().toString());       // category rename
		assertThat(search("salon")).isEmpty();
		assertThat(search("bordeaux")).containsExactly(p.id().toString());       // option label change
		assertThat(search("rouge")).isEmpty();
	}

	@Test
	void addingAndRemovingAVariantSkuUpdatesTheSearch() throws Exception {
		var p = simple("Soulier a variantes", null);
		var variant = TestData.variant(unique("NEWV").replace("-", ""), Map.of(), Map.of());
		assertThat(search(variant.sku())).isEmpty();
		var withVariant = catalog.saveProduct(new Product(p.id(), p.sku(), p.slug(), p.categoryId(), p.name(), p.description(), p.brand(), p.unit(), p.unitNote(), p.status(), p.pricing(),
				List.of(variant), p.attributes(), p.images(), p.createdAt(), Instant.now()), staff);
		assertThat(search(variant.sku())).containsExactly(p.id().toString());
		catalog.saveProduct(new Product(p.id(), p.sku(), p.slug(), p.categoryId(), p.name(), p.description(), p.brand(), p.unit(), p.unitNote(), p.status(), p.pricing(),
				List.of(), p.attributes(), p.images(), p.createdAt(), Instant.now()), staff);
		assertThat(search(variant.sku())).isEmpty();
		assertThat(withVariant.variants()).hasSize(1);
	}

	@Test
	void aRolledBackSaveLeavesTheOldSearchVectorUntouched() throws Exception {
		var p = simple("Stable avant echec", null);
		assertThatThrownBy(() -> new TransactionTemplate(tm).executeWithoutResult(s -> {
			rename(p, "Change pendant echec", ProductStatus.ACTIVE);
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(search("stable")).containsExactly(p.id().toString());
		assertThat(search("pendant")).isEmpty();
	}

	@Test
	void reindexRebuildsACorruptedIndexAndIsIdempotentAndStaffOnlyAndAudited() throws Exception {
		var p = simple("Reindexable article", null);
		var admin = newStaffToken();
		// simulate a stale index (e.g. restored from an old backup): clear this product's vector through the same port the use cases use
		new TransactionTemplate(tm).executeWithoutResult(s -> searchIndex.index(p.id(), new com.anadepot.core.catalog.model.SearchDocument("", "", "", "")));
		assertThat(search("reindexable")).isEmpty();

		post("/api/v1/admin/catalog/search/reindex", Map.of(), null, null).andExpect(status().isUnauthorized());
		post("/api/v1/admin/catalog/search/reindex", Map.of(), token(UUID.randomUUID()), null).andExpect(status().isForbidden());
		post("/api/v1/admin/catalog/search/reindex", Map.of(), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.indexed").isNumber());
		assertThat(search("reindexable")).containsExactly(p.id().toString());
		post("/api/v1/admin/catalog/search/reindex", Map.of(), admin, null).andExpect(status().isOk());      // repeating changes nothing
		assertThat(search("reindexable")).containsExactly(p.id().toString());
		assertThat(auditRepo.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("catalog", "search")).extracting(e -> e.getAction()).contains("SEARCH_REINDEXED");
	}

	@Autowired com.anadepot.core.catalog.repository.ProductSearchIndex searchIndex;
	@Autowired com.anadepot.infra.persistence.AuditEventJpaRepository auditRepo;
}
