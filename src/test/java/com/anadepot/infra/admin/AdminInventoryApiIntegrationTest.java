package com.anadepot.infra.admin;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff stock management over HTTP: real security chain, real use cases, PostgreSQL. */
class AdminInventoryApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BASE = "/api/v1/admin/inventory";
	@Autowired AuditEventJpaRepository audits;

	private TestData.CategoryFixture fx;
	private String staffToken;

	@BeforeEach
	void setUp() {
		fx = TestData.category();
		catalog.saveCategory(fx.category());
		staffToken = newStaffToken();
	}

	private Product product(String name) {
		var base = TestData.product(fx.category().id(), ProductStatus.ACTIVE, price(htg("100.00"), null, 1));
		return catalog.saveProduct(new Product(base.id(), base.sku(), base.slug(), base.categoryId(), new com.anadepot.core.shared.LocalizedText(Map.of("fr", name, "ht", name)), base.description(), null,
				base.unit(), base.unitNote(), ProductStatus.ACTIVE, base.pricing(), List.of(), List.of(), List.of(), TestData.T0, TestData.T0), staff);
	}

	private JsonNode register(UUID productId, Object... extra) throws Exception {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("productId", productId);
		for (int i = 0; i < extra.length; i += 2) {
			body.put((String) extra[i], extra[i + 1]);
		}
		return JSON.readTree(post(BASE, body, staffToken, null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private int adjust(String itemId, String type, int quantity, String reason, String key) throws Exception {
		return post(BASE + "/" + itemId + "/adjustments", Map.of("type", type, "quantity", quantity, "reason", reason), staffToken, key).andReturn().getResponse().getStatus();
	}

	private JsonNode read(String id) throws Exception {
		return JSON.readTree(get(BASE + "/" + id, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryInventoryEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get(BASE, bearer).andExpect(status().is(expected));
			get(BASE + "/" + id, bearer).andExpect(status().is(expected));
			post(BASE, Map.of("productId", id), bearer, null).andExpect(status().is(expected));
			post(BASE + "/" + id + "/adjustments", Map.of("type", "ADD", "quantity", 1, "reason", "RECEPTION"), bearer, key()).andExpect(status().is(expected));
			send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/threshold").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"), bearer).andExpect(status().is(expected));
			get(BASE + "/" + id + "/movements", bearer).andExpect(status().is(expected));
		}
	}

	@Test
	void staffRegistersAStockPositionWithAnOpeningBalanceAndItIsAudited() throws Exception {
		var p = product("Chaise " + unique("r"));
		var created = register(p.id(), "initialOnHand", 12, "lowStockThreshold", 3);
		var id = created.get("id").asString();
		assertThat(created.get("onHand").asInt()).isEqualTo(12);
		assertThat(created.get("reserved").asInt()).isZero();
		assertThat(created.get("available").asInt()).isEqualTo(12);
		assertThat(created.get("state").asString()).isEqualTo("OK");
		assertThat(created.get("productSku").asString()).isEqualTo(p.sku());
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("inventory_item", id)).extracting(a -> a.getAction()).contains("INVENTORY_REGISTERED");
		get(BASE + "/" + id + "/movements", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].type").value("INITIAL")).andExpect(jsonPath("$.items[0].balanceAfter").value(12));

		post(BASE, Map.of("productId", p.id(), "initialOnHand", 1), staffToken, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("inventory_exists"));
		post(BASE, Map.of("productId", UUID.randomUUID()), staffToken, null).andExpect(status().isNotFound());
		post(BASE, Map.of("productId", product("x " + unique("n")).id(), "initialOnHand", -1), staffToken, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='initialOnHand')]").isNotEmpty());
		post(BASE, Map.of("productId", product("y " + unique("n")).id(), "tracked", false, "initialOnHand", 4), staffToken, null).andExpect(status().isUnprocessableContent());
		var untracked = register(product("z " + unique("n")).id(), "tracked", false);
		assertThat(untracked.get("state").asString()).isEqualTo("UNTRACKED");
		assertThat(untracked.get("onHand").isNull()).isTrue();
		var untrackedId = untracked.get("id").asString();
		assertThat(adjust(untrackedId, "ADD", 1, "RECEPTION", key())).isEqualTo(422);      // inventory_not_tracked
	}

	@Test
	void aProductWithVariantsIsStockedPerVariant() throws Exception {
		var base = TestData.product(fx.category().id(), ProductStatus.ACTIVE, price(htg("100.00"), null, 1));
		var v = TestData.variant(unique("V"), Map.of(fx.sizeAttribute(), fx.sizeM()), Map.of());
		var p = catalog.saveProduct(new Product(base.id(), base.sku(), base.slug(), base.categoryId(), base.name(), base.description(), null, base.unit(), base.unitNote(), ProductStatus.ACTIVE,
				base.pricing(), List.of(v), List.of(), List.of(), TestData.T0, TestData.T0), staff);
		post(BASE, Map.of("productId", p.id(), "initialOnHand", 2), staffToken, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("variant_required"));
		post(BASE, Map.of("productId", p.id(), "variantId", UUID.randomUUID(), "initialOnHand", 2), staffToken, null).andExpect(status().isNotFound());
		var created = register(p.id(), "variantId", v.id(), "initialOnHand", 7);
		assertThat(created.get("variantSku").asString()).isEqualTo(v.sku());
		assertThat(created.get("onHand").asInt()).isEqualTo(7);
	}

	@Test
	void adjustmentsAddReduceAndRecountNeverGoNegativeAndNeedAReasonAndAKey() throws Exception {
		var item = register(product("A " + unique("a")).id(), "initialOnHand", 10);
		var id = item.get("id").asString();
		assertThat(adjust(id, "ADD", 5, "RECEPTION", key())).isEqualTo(201);
		assertThat(read(id).get("onHand").asInt()).isEqualTo(15);
		assertThat(adjust(id, "REDUCE", 3, "DAMAGE", key())).isEqualTo(201);
		assertThat(read(id).get("onHand").asInt()).isEqualTo(12);
		assertThat(adjust(id, "RECOUNT", 9, "COUNT", key())).isEqualTo(201);
		assertThat(read(id).get("onHand").asInt()).isEqualTo(9);
		assertThat(adjust(id, "REDUCE", 10, "LOSS", key())).isEqualTo(422);                  // would go below zero: refused, nothing changes
		assertThat(read(id).get("onHand").asInt()).isEqualTo(9);
		assertThat(adjust(id, "REDUCE", 0, "LOSS", key())).isEqualTo(422);
		assertThat(adjust(id, "SALE", 1, "OTHER", key())).isEqualTo(422);                    // sales are made by collecting reservations, not by hand
		assertThat(adjust(id, "ADD", 1, "SALE", key())).isEqualTo(422);
		assertThat(adjust(id, "ADD", 1, "NOPE", key())).isEqualTo(422);
		post(BASE + "/" + id + "/adjustments", Map.of("type", "ADD", "quantity", 1, "reason", "RECEPTION"), staffToken, null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("idempotency_key_required"));
		post(BASE + "/" + id + "/adjustments", Map.of("type", "ADD"), staffToken, key()).andExpect(status().isUnprocessableContent());
		post(BASE + "/" + UUID.randomUUID() + "/adjustments", Map.of("type", "ADD", "quantity", 1, "reason", "RECEPTION"), staffToken, key()).andExpect(status().isNotFound());

		var movements = JSON.readTree(get(BASE + "/" + id + "/movements?size=10", staffToken).andReturn().getResponse().getContentAsString());
		assertThat(movements.get("totalItems").asInt()).isEqualTo(4);                          // initial + add + reduce + recount
		assertThat(movements.get("items").get(0).get("type").asString()).isEqualTo("RECOUNT");   // newest first
		assertThat(movements.get("items").get(0).get("delta").asInt()).isEqualTo(-3);
		assertThat(movements.get("items").get(0).get("recordedBy")).isNotNull();
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("inventory_item", id).stream().filter(a -> "INVENTORY_ADJUSTED".equals(a.getAction())).count()).isEqualTo(3);
	}

	@Test
	void aRetriedAdjustmentIsAppliedOnceAndAKeyReusedWithOtherDataIsAConflict() throws Exception {
		var id = register(product("I " + unique("i")).id(), "initialOnHand", 10).get("id").asString();
		var key = key();
		var body = Map.of("type", "ADD", "quantity", 4, "reason", "RECEPTION");
		var first = JSON.readTree(post(BASE + "/" + id + "/adjustments", body, staffToken, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var again = JSON.readTree(post(BASE + "/" + id + "/adjustments", body, staffToken, key).andReturn().getResponse().getContentAsString());
		assertThat(again.get("id")).isEqualTo(first.get("id"));
		assertThat(read(id).get("onHand").asInt()).isEqualTo(14);                              // not 18
		post(BASE + "/" + id + "/adjustments", Map.of("type", "ADD", "quantity", 5, "reason", "RECEPTION"), staffToken, key).andExpect(status().isConflict());
		assertThat(read(id).get("onHand").asInt()).isEqualTo(14);
	}

	@Test
	void concurrentReductionsNeverOversellOrGoNegative() throws Exception {
		var id = register(product("C " + unique("c")).id(), "initialOnHand", 10).get("id").asString();
		int threads = 8;
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(threads);
		var results = new ArrayList<java.util.concurrent.Future<Integer>>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return adjust(id, "REDUCE", 3, "LOSS", key());
			}));
		}
		start.countDown();
		int ok = 0;
		int refused = 0;
		for (var f : results) {
			int code = f.get();
			if (code == 201) {
				ok++;
			} else if (code == 422) {
				refused++;
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(3);                      // 10 units, 3 each: exactly three succeed
		assertThat(refused).isEqualTo(threads - 3);
		assertThat(read(id).get("onHand").asInt()).isEqualTo(1);
	}

	@Test
	void adjustmentsNeverTouchReservedStockAndAvailableIsOnHandMinusReserved() throws Exception {
		ensurePickupAvailable();
		var mattress = stockedMattress(10);
		var id = JSON.readTree(get(BASE + "?productId=" + mattress.id(), staffToken).andReturn().getResponse().getContentAsString()).get("items").get(0).get("id").asString();
		submit(key(), line(mattress, 4, com.anadepot.core.shared.money.SupportedCurrency.HTG));
		var after = read(id);
		assertThat(after.get("reserved").asInt()).isEqualTo(4);
		assertThat(after.get("available").asInt()).isEqualTo(6);
		assertThat(adjust(id, "ADD", 5, "RECEPTION", key())).isEqualTo(201);
		var added = read(id);
		assertThat(added.get("onHand").asInt()).isEqualTo(15);
		assertThat(added.get("reserved").asInt()).isEqualTo(4);                               // reservations own this number
		assertThat(added.get("available").asInt()).isEqualTo(11);
		// the customer-facing API still only exposes availableQuantity
		var publicView = get("/api/v1/products/" + mattress.slug(), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(publicView).contains("availableQuantity").doesNotContain("onHand").doesNotContain("reserved");
	}

	@Test
	void thresholdUpdateNeedsTheVersionAndCanBeCleared() throws Exception {
		var item = register(product("T " + unique("t")).id(), "initialOnHand", 5);
		var id = item.get("id").asString();
		var version = item.get("version").asLong();
		var patch = MockMvcRequestBuilders.patch(BASE + "/" + id + "/threshold").contentType(MediaType.APPLICATION_JSON);
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/threshold").contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":2}"), staffToken).andExpect(status().isUnprocessableContent());
		var updated = JSON.readTree(send(patch.content("{\"version\":" + version + ",\"lowStockThreshold\":5}"), staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("LOW")).andReturn()
				.getResponse().getContentAsString());
		assertThat(updated.get("version").asLong()).isGreaterThan(version);
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/threshold").contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + ",\"lowStockThreshold\":1}"), staffToken)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_update"));
		send(MockMvcRequestBuilders.patch(BASE + "/" + id + "/threshold").contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + updated.get("version").asLong() + ",\"lowStockThreshold\":null}"), staffToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OK")).andExpect(jsonPath("$.lowStockThreshold").doesNotExist());
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("inventory_item", id)).extracting(a -> a.getAction()).contains("INVENTORY_THRESHOLD_CHANGED");
	}

	@Test
	void listFiltersByStateSearchProductAndSortsWhatNeedsRestockingFirst() throws Exception {
		var tag = unique("lst").replace("-", "");
		var ok = register(product("Alpha " + tag).id(), "initialOnHand", 20, "lowStockThreshold", 5).get("id").asString();
		var low = register(product("Beta " + tag).id(), "initialOnHand", 3, "lowStockThreshold", 5).get("id").asString();
		var out = register(product("Gamma " + tag).id(), "initialOnHand", 0).get("id").asString();
		var untracked = register(product("Delta " + tag).id(), "tracked", false).get("id").asString();

		get(BASE + "?q=" + tag, staffToken).andExpect(jsonPath("$.totalItems").value(4));
		get(BASE + "?q=" + tag + "&state=OK", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(ok));
		get(BASE + "?q=" + tag + "&state=LOW", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(low));
		get(BASE + "?q=" + tag + "&state=OUT", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(out));
		get(BASE + "?q=" + tag + "&state=UNTRACKED", staffToken).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(untracked));
		get(BASE + "?q=" + tag + "&sort=AVAILABLE_ASC", staffToken).andExpect(jsonPath("$.items[0].id").value(out)).andExpect(jsonPath("$.items[1].id").value(low))
				.andExpect(jsonPath("$.items[2].id").value(ok)).andExpect(jsonPath("$.items[3].id").value(untracked));
		get(BASE + "?q=beta+" + tag.substring(0, 3), staffToken).andExpect(status().isOk());
		get(BASE + "?q=Beta " + tag, staffToken).andExpect(jsonPath("$.totalItems").value(1));
		get(BASE + "?categoryId=" + fx.category().id() + "&size=2", staffToken).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalItems").value(4));
		get(BASE + "?state=NOPE", staffToken).andExpect(status().isUnprocessableContent());
		get(BASE + "?q=%25_%5C", staffToken).andExpect(status().isOk());
		get(BASE + "/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound());
	}
}
