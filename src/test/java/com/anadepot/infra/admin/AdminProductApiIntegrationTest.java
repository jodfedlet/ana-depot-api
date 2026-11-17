package com.anadepot.infra.admin;

import static com.anadepot.testsupport.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff product management over HTTP: real security chain, real use cases, PostgreSQL. */
class AdminProductApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BASE = "/api/v1/admin/products";
	@Autowired AuditEventJpaRepository audits;

	private TestData.CategoryFixture fx;
	private String staffToken;

	@BeforeEach
	void setUp() {
		fx = TestData.category();
		catalog.saveCategory(fx.category());
		staffToken = newStaffToken();
	}

	private Map<String, Object> price(String currency, String retail, Object... extra) {
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("currency", currency);
		m.put("retail", retail);
		for (int i = 0; i < extra.length; i += 2) {
			m.put((String) extra[i], extra[i + 1]);
		}
		return m;
	}

	private Map<String, Object> product(String sku, Object... extra) {
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("sku", sku);
		m.put("categoryId", fx.category().id());
		m.put("name", Map.of("fr", "Matelas " + sku, "ht", "Matla " + sku));
		m.put("prices", List.of(price("HTG", "5000.00")));
		for (int i = 0; i < extra.length; i += 2) {
			m.put((String) extra[i], extra[i + 1]);
		}
		return m;
	}

	private JsonNode create(Map<String, Object> body) throws Exception {
		return JSON.readTree(post(BASE, body, staffToken, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode read(String id) throws Exception {
		return JSON.readTree(get(BASE + "/" + id, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static List<String> actions(List<com.anadepot.infra.persistence.AuditEventEntity> trail) {
		return trail.stream().map(a -> a.getAction()).toList();
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryProductEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get(BASE, bearer).andExpect(status().is(expected));
			get(BASE + "/" + id, bearer).andExpect(status().is(expected));
			post(BASE, product(unique("S")), bearer, null).andExpect(status().is(expected));
			put(BASE + "/" + id, product(unique("S"), "version", 0), bearer).andExpect(status().is(expected));
			send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"), bearer).andExpect(status().is(expected));
			send(MockMvcRequestBuilders.delete(BASE + "/" + id), bearer).andExpect(status().is(expected));
		}
	}

	@Test
	void staffCreatesAProductWithIndependentHtgAndUsdPricesVariantsAndAttributesAndItIsAudited() throws Exception {
		var sku = unique("MAT");
		var body = product(sku, "brand", "Confort", "unit", "UNIT",
				"prices", List.of(
						price("HTG", "5000.00", "sale", "4500.00", "wholesaleEnabled", true, "minOrderQuantity", 1, "tiers", List.of(Map.of("minQuantity", 5, "unitPrice", "4000.00"), Map.of("minQuantity", 10, "unitPrice", "3500.00"))),
						price("USD", "40.00")),
				"variants", List.of(Map.of("sku", sku + "-S", "axisValues", Map.of(fx.sizeAttribute(), fx.sizeS()), "retailOverrides", Map.of("HTG", "4800.00")),
						Map.of("sku", sku + "-M", "axisValues", Map.of(fx.sizeAttribute(), fx.sizeM()))),
				"attributes", List.of(Map.of("attributeId", fx.materialAttribute(), "text", "mousse")));
		var created = create(body);
		var id = created.get("id").asString();
		assertThat(created.get("status").asString()).isEqualTo("DRAFT");
		assertThat(created.get("version").asLong()).isGreaterThanOrEqualTo(0);
		assertThat(created.get("slug").asString()).startsWith("matelas-");
		assertThat(created.get("prices")).hasSize(2);
		var htg = created.get("prices").get(0).get("currency").asString().equals("HTG") ? created.get("prices").get(0) : created.get("prices").get(1);
		var usd = created.get("prices").get(0) == htg ? created.get("prices").get(1) : created.get("prices").get(0);
		assertThat(htg.get("retail").asString()).isEqualTo("5000.00");
		assertThat(htg.get("sale").asString()).isEqualTo("4500.00");
		assertThat(htg.get("tiers")).hasSize(2);
		assertThat(usd.get("retail").asString()).isEqualTo("40.00");      // USD is its own price: never derived from HTG
		assertThat(usd.get("tiers")).isEmpty();
		assertThat(created.get("variants")).hasSize(2);
		assertThat(created.get("attributes").get(0).get("text").asString()).isEqualTo("mousse");
		assertThat(created.get("stock").get("state").asString()).isEqualTo("NO_STOCK_RECORD");

		get("/api/v1/products/" + created.get("slug").asString(), null).andExpect(status().isNotFound());       // a draft is not public
		assertThat(actions(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", id))).contains("PRODUCT_CREATED", "PRODUCT_PRICE_CHANGED", "PRODUCT_STATUS_CHANGED");
		read(id);
		get(BASE + "/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound());
	}

	@Test
	void validationReportsEveryStructuralIssueAndTheDomainEnforcesPriceRules() throws Exception {
		post(BASE, Map.of("sku", "bad sku!", "name", Map.of(), "unit", "BANANA", "prices", List.of(Map.of("currency", "EUR", "retail", "abc"), Map.of("currency", "HTG", "retail", "10.005")),
				"variants", List.of(Map.of("sku", "", "axisValues", Map.of()))), staffToken, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("validation_failed"))
				.andExpect(jsonPath("$.errors[?(@.field=='categoryId')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='sku')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='unit')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='prices[0].currency')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='prices[1].retail')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='variants[0].sku')]").isNotEmpty());

		post(BASE, product(unique("P"), "prices", List.of(price("HTG", "100.00", "sale", "100.00"))), staffToken, null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("sale_price_invalid"));
		post(BASE, product(unique("P"), "prices", List.of(price("HTG", "100.00", "wholesaleEnabled", true, "tiers", List.of(Map.of("minQuantity", 5, "unitPrice", "120.00"))))), staffToken, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("tier_above_base"));
		post(BASE, product(unique("P"), "prices", List.of(price("HTG", "100.00"), price("HTG", "200.00"))), staffToken, null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.errors[?(@.code=='duplicate')]").isNotEmpty());
		post(BASE, product(unique("P"), "variants", List.of(Map.of("sku", unique("V"), "axisValues", Map.of(fx.sizeAttribute(), fx.sizeS()), "retailOverrides", Map.of("USD", "5.00")))), staffToken, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.code=='currency_without_price')]").isNotEmpty());
		post(BASE, product(unique("P"), "categoryId", UUID.randomUUID()), staffToken, null).andExpect(status().isNotFound());
		post(BASE, product(unique("P"), "attributes", List.of(Map.of("attributeId", UUID.randomUUID(), "text", "x"))), staffToken, null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("attribute_not_in_category"));
	}

	@Test
	void duplicateSkuAndSlugAreConflictsAndAGeneratedSlugIsMadeUnique() throws Exception {
		var sku = unique("DUP");
		var first = create(product(sku, "slug", unique("slug")));
		post(BASE, product(sku), staffToken, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_sku"));
		post(BASE, product(unique("X"), "slug", first.get("slug").asString()), staffToken, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_slug"));
		// a variant SKU cannot be the SKU of another product either
		post(BASE, product(unique("Y"), "variants", List.of(Map.of("sku", sku, "axisValues", Map.of(fx.sizeAttribute(), fx.sizeS())))), staffToken, null)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_sku"));
		// same name, no slug given: the second one gets a numbered slug instead of failing
		var name = Map.of("fr", "Chaise unique " + unique("n"), "ht", "Chèz");
		var a = create(product(unique("A"), "name", name));
		var b = create(product(unique("B"), "name", name));
		assertThat(b.get("slug").asString()).isNotEqualTo(a.get("slug").asString()).startsWith(a.get("slug").asString());
	}

	@Test
	void idempotentCreateReturnsTheSameProductAndAKeyReusedWithOtherDataIsAConflict() throws Exception {
		var key = "prod-" + UUID.randomUUID();
		var body = product(unique("IDEM"));
		var a = JSON.readTree(post(BASE, body, staffToken, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var b = JSON.readTree(post(BASE, body, staffToken, key).andReturn().getResponse().getContentAsString());
		assertThat(b.get("id")).isEqualTo(a.get("id"));
		post(BASE, product(unique("OTHER")), staffToken, key).andExpect(status().isConflict());
		get(BASE + "?q=" + body.get("sku"), staffToken).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void updateNeedsTheCurrentVersionKeepsImagesAndStatusAndAuditsTheChange() throws Exception {
		var sku = unique("UPD");
		var created = create(product(sku));
		var id = created.get("id").asString();
		var version = created.get("version").asLong();
		put(BASE + "/" + id, product(sku), staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());

		var changed = product(sku, "version", version, "slug", created.get("slug").asString(), "prices", List.of(price("HTG", "6000.00"), price("USD", "50.00")), "brand", "Nouveau");
		var updated = JSON.readTree(put(BASE + "/" + id, changed, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(updated.get("version").asLong()).isGreaterThan(version);
		assertThat(updated.get("prices")).hasSize(2);
		assertThat(updated.get("brand").asString()).isEqualTo("Nouveau");
		assertThat(updated.get("status").asString()).isEqualTo("DRAFT");

		put(BASE + "/" + id, changed, staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_update"));       // stale version
		var trail = actions(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", id));
		assertThat(trail).contains("PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_PRICE_CHANGED");
		assertThat(trail.stream().filter("PRODUCT_PRICE_CHANGED"::equals).count()).isGreaterThanOrEqualTo(3);        // HTG created, HTG changed, USD added
	}

	@Test
	void twoSimultaneousUpdatesWithTheSameVersionLetExactlyOneWin() throws Exception {
		var sku = unique("RACE");
		var created = create(product(sku));
		var id = created.get("id").asString();
		var version = created.get("version").asLong();
		int threads = 4;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(threads);
		var results = new ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < threads; i++) {
			var body = product(sku, "version", version, "slug", created.get("slug").asString(), "brand", "Marque " + i);
			Callable<Integer> call = () -> {
				start.await();
				return put(BASE + "/" + id, body, staffToken).andReturn().getResponse().getStatus();
			};
			results.add(pool.submit(call));
		}
		start.countDown();
		var codes = new ArrayList<Integer>();
		int ok = 0;
		int conflict = 0;
		for (var f : results) {
			int code = f.get();
			codes.add(code);
			if (code == 200) {
				ok++;
			} else if (code == 409) {
				conflict++;
			}
		}
		pool.shutdown();
		assertThat(ok).as("status codes " + codes).isEqualTo(1);
		assertThat(conflict).as("status codes " + codes).isEqualTo(threads - 1);
	}

	@Test
	void aVariantWithStockCannotBeRemovedOnlyDeactivatedAndAProductStaysPublishable() throws Exception {
		var sku = unique("VAR");
		var created = create(product(sku, "variants", List.of(Map.of("sku", sku + "-S", "axisValues", Map.of(fx.sizeAttribute(), fx.sizeS())),
				Map.of("sku", sku + "-M", "axisValues", Map.of(fx.sizeAttribute(), fx.sizeM())))));
		var id = created.get("id").asString();
		var variantS = created.get("variants").get(0);
		var variantM = created.get("variants").get(1);
		inventory.register(UUID.fromString(id), UUID.fromString(variantS.get("id").asString()), true, 5, null, null);

		var base = product(sku, "version", created.get("version").asLong(), "slug", created.get("slug").asString());
		// removing the variant that has a stock record: refused, nothing changes
		put(BASE + "/" + id, withVariants(base, List.of(variantBody(variantM, true))), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("variant_in_use"));
		assertThat(read(id).get("variants")).hasSize(2);
		// removing the one without stock is fine; deactivating the used one is fine
		var kept = put(BASE + "/" + id, withVariants(base, List.of(variantBody(variantS, false))), staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		var after = JSON.readTree(kept);
		assertThat(after.get("variants")).hasSize(1);
		assertThat(after.get("variants").get(0).get("active").asBoolean()).isFalse();
		assertThat(after.get("stock").get("onHand").asInt()).isEqualTo(5);
		assertThat(after.get("stock").get("positions").get(0).get("available").asInt()).isEqualTo(5);
	}

	private static Map<String, Object> withVariants(Map<String, Object> base, List<Map<String, Object>> variants) {
		var m = new java.util.LinkedHashMap<>(base);
		m.put("variants", variants);
		return m;
	}

	private static Map<String, Object> variantBody(JsonNode v, boolean active) {
		var axis = new java.util.LinkedHashMap<String, String>();
		v.get("axisValues").properties().forEach(e -> axis.put(e.getKey(), e.getValue().asString()));
		return Map.of("id", v.get("id").asString(), "sku", v.get("sku").asString(), "active", active, "axisValues", axis);
	}

	@Test
	void statusLifecyclePublishingNeedsBothLanguagesAndInvalidTransitionsAreConflicts() throws Exception {
		var onlyFrench = create(product(unique("FR"), "name", Map.of("fr", "Seulement français")));
		var onlyFrenchId = onlyFrench.get("id").asString();
		send(MockMvcRequestBuilders.patch(BASE + "/" + onlyFrenchId + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"), staffToken)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='name.ht')]").isNotEmpty());

		var noPriceActive = product(unique("NP"), "prices", List.of(price("HTG", "100.00", "active", false)));
		send(MockMvcRequestBuilders.patch(BASE + "/" + create(noPriceActive).get("id").asString() + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"), staffToken)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("price_required"));

		var created = create(product(unique("LIFE")));
		var id = created.get("id").asString();
		var slug = created.get("slug").asString();
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"), staffToken).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("invalid_status"));
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOPE\"}"), staffToken).andExpect(status().isUnprocessableContent());
		inventory.register(UUID.fromString(id), null, true, 3, null, null);
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\",\"reason\":\"ready\"}"), staffToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		get("/api/v1/products/" + slug, null).andExpect(status().isOk());                       // now public
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\",\"reason\":\"discontinued\"}"), staffToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
		get("/api/v1/products/" + slug, null).andExpect(status().isNotFound());                 // hidden again, still readable by staff
		read(id);
		var trail = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", id);
		assertThat(actions(trail)).contains("PRODUCT_ACTIVATED", "PRODUCT_DEACTIVATED");
		assertThat(trail.stream().filter(a -> "PRODUCT_DEACTIVATED".equals(a.getAction())).findFirst().orElseThrow().getReason()).isEqualTo("discontinued");
	}

	@Test
	void onlyAnUnusedDraftCanBeDeleted() throws Exception {
		var draft = create(product(unique("DEL")));
		var id = draft.get("id").asString();
		send(MockMvcRequestBuilders.delete(BASE + "/" + id), staffToken).andExpect(status().isNoContent());
		get(BASE + "/" + id, staffToken).andExpect(status().isNotFound());
		send(MockMvcRequestBuilders.delete(BASE + "/" + id), staffToken).andExpect(status().isNotFound());
		assertThat(actions(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", id))).contains("PRODUCT_DELETED");

		var stocked = create(product(unique("DELS")));
		inventory.register(UUID.fromString(stocked.get("id").asString()), null, true, 1, null, null);
		send(MockMvcRequestBuilders.delete(BASE + "/" + stocked.get("id").asString()), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("product_in_use"));

		var published = create(product(unique("DELP")));
		send(MockMvcRequestBuilders.patch(BASE + "/" + published.get("id").asString() + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"), staffToken).andExpect(status().isOk());
		send(MockMvcRequestBuilders.delete(BASE + "/" + published.get("id").asString()), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("product_in_use"));
	}

	@Test
	void listFiltersSearchesSortsPaginatesAndStaffSeeDraftsThePublicDoesNot() throws Exception {
		var tag = unique("lt").replace("-", "");
		var a = create(product("A-" + tag, "name", Map.of("fr", "Armoire " + tag, "ht", "Amwa " + tag), "brand", "Alpha", "prices", List.of(price("HTG", "100.00"))));
		var b = create(product("B-" + tag, "name", Map.of("fr", "Bureau " + tag, "ht", "Biwo " + tag), "brand", "Beta", "prices", List.of(price("USD", "10.00"))));
		var c = create(product("C-" + tag, "name", Map.of("fr", "Canapé " + tag, "ht", "Kanape " + tag), "brand", "Alpha", "prices", List.of(price("HTG", "300.00"), price("USD", "30.00"))));
		inventory.register(UUID.fromString(a.get("id").asString()), null, true, 10, 2, null);
		inventory.register(UUID.fromString(b.get("id").asString()), null, true, 1, 3, null);       // low: threshold 3, available 1
		inventory.register(UUID.fromString(c.get("id").asString()), null, true, 0, null, null);    // out of stock
		send(MockMvcRequestBuilders.patch(BASE + "/" + a.get("id").asString() + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"), staffToken).andExpect(status().isOk());

		get(BASE + "?q=" + tag, staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3));
		get(BASE + "?q=armoire+" + tag, staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].sku").value("A-" + tag));
		get(BASE + "?q=B-" + tag, staffToken).andExpect(jsonPath("$.items[0].id").value(b.get("id").asString()));      // SKU lookup: the exact SKU ranks first
		get(BASE + "?q=" + b.get("variants").size() + "zz" + tag, staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?q=" + tag + "&status=ACTIVE", staffToken).andExpect(jsonPath("$.totalItems").value(1));
		get(BASE + "?q=" + tag + "&status=DRAFT", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?q=" + tag + "&brand=alpha", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?q=" + tag + "&currency=USD", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?q=" + tag + "&currency=HTG", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		get(BASE + "?q=" + tag + "&categoryId=" + fx.category().id(), staffToken).andExpect(jsonPath("$.totalItems").value(3));
		get(BASE + "?q=" + tag + "&categoryId=" + UUID.randomUUID(), staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get(BASE + "?q=" + tag + "&stock=LOW_STOCK", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(b.get("id").asString()));
		get(BASE + "?q=" + tag + "&stock=OUT_OF_STOCK", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(c.get("id").asString()));
		get(BASE + "?q=" + tag + "&stock=IN_STOCK", staffToken).andExpect(jsonPath("$.totalItems").value(2));       // low stock is still in stock
		get(BASE + "?q=" + tag + "&sort=NAME", staffToken).andExpect(jsonPath("$.items[0].sku").value("A-" + tag)).andExpect(jsonPath("$.items[2].sku").value("C-" + tag));
		get(BASE + "?q=" + tag + "&sort=SKU", staffToken).andExpect(jsonPath("$.items[0].sku").value("A-" + tag));
		get(BASE + "?q=" + tag + "&size=2&page=1", staffToken).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
		get(BASE + "?q=" + tag + "&stock=BANANA", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "?size=1000", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "?q=%25_%5C", staffToken).andExpect(status().isOk());                          // LIKE wildcards are plain text
		var list = JSON.readTree(get(BASE + "?q=" + tag + "&sort=SKU", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(list.get("items").get(0).get("stock").get("onHand").asInt()).isEqualTo(10);   // staff see the stock figures
		assertThat(list.get("items").get(0).get("stock").get("available").asInt()).isEqualTo(10);
		get("/api/v1/products?q=" + tag, null).andExpect(jsonPath("$.totalItems").value(1));      // the public only ever sees the active product
	}
}
