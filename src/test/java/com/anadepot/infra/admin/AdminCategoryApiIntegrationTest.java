package com.anadepot.infra.admin;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff category management over HTTP: real security chain, real use cases, PostgreSQL. */
class AdminCategoryApiIntegrationTest extends ApiTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();
	@Autowired AuditEventJpaRepository audits;

	private static Map<String, Object> body(String slug, Object... extra) {
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("slug", slug);
		m.put("name", Map.of("fr", "Catégorie " + slug, "ht", "Kategori " + slug));
		for (int i = 0; i < extra.length; i += 2) {
			m.put((String) extra[i], extra[i + 1]);
		}
		return m;
	}

	private static Map<String, Object> choice(String key, boolean required, boolean axis, String... codes) {
		var options = new ArrayList<Map<String, Object>>();
		for (var c : codes) {
			options.add(Map.of("code", c, "label", Map.of("fr", c, "ht", c)));
		}
		return Map.of("key", key, "label", Map.of("fr", key, "ht", key), "type", "CHOICE", "required", required, "filterable", true, "variantAxis", axis, "options", options);
	}

	private JsonNode create(String token, Map<String, Object> body) throws Exception {
		var res = post("/api/v1/admin/categories", body, token, null).andExpect(status().isCreated()).andReturn();
		return JSON.readTree(res.getResponse().getContentAsString());
	}

	@Test
	void anonymousIs401AndNonStaffIs403OnEveryCategoryEndpoint() throws Exception {
		var id = UUID.randomUUID();
		var customer = token(UUID.randomUUID());
		for (var bearer : new String[] { null, customer }) {
			int expected = bearer == null ? 401 : 403;
			get("/api/v1/admin/categories", bearer).andExpect(status().is(expected));
			get("/api/v1/admin/categories/" + id, bearer).andExpect(status().is(expected));
			post("/api/v1/admin/categories", body(unique("s")), bearer, null).andExpect(status().is(expected));
			put("/api/v1/admin/categories/" + id, body(unique("s"), "version", 0), bearer).andExpect(status().is(expected));
			send(MockMvcRequestBuilders.patch("/api/v1/admin/categories/" + id + "/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), bearer).andExpect(status().is(expected));
			put("/api/v1/admin/categories/order", Map.of("categoryIds", List.of()), bearer).andExpect(status().is(expected));
			get("/api/v1/admin/categories/" + id + "/products", bearer).andExpect(status().is(expected));
			post("/api/v1/admin/categories/" + id + "/products/move", Map.of(), bearer, null).andExpect(status().is(expected));
		}
	}

	@Test
	void staffCreatesReadsAndListsACategoryWithAttributesAndAuditsIt() throws Exception {
		var staffToken = newStaffToken();
		var slug = unique("matelas");
		var created = create(staffToken, body(slug, "attributes", List.of(choice("size", true, true, "s", "m", "l"))));
		var id = created.get("id").asString();
		assertThat(created.get("version").asLong()).isZero();
		assertThat(created.get("attributes").get(0).get("options")).hasSize(3);
		assertThat(created.get("productCount").asLong()).isZero();

		get("/api/v1/admin/categories/" + id, staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug)).andExpect(jsonPath("$.name.ht").value("Kategori " + slug));
		get("/api/v1/admin/categories?q=" + slug, staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
		get("/api/v1/admin/categories?active=false&q=" + slug, staffToken).andExpect(jsonPath("$.totalItems").value(0));
		get("/api/v1/admin/categories/" + UUID.randomUUID(), staffToken).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));

		var trail = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("category", id);
		assertThat(trail).extracting(a -> a.getAction()).containsExactly("CATEGORY_CREATED");
	}

	@Test
	void validationReportsEveryFieldIssueAndNeverCreates() throws Exception {
		var staffToken = newStaffToken();
		post("/api/v1/admin/categories", Map.of("slug", "Bad Slug", "name", Map.of(), "attributes", List.of(Map.of("key", "Bad", "type", "CHOICE"))), staffToken, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("validation_failed"))
				.andExpect(jsonPath("$.errors[?(@.field=='slug')]").isNotEmpty()).andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='attributes[0].key')]").isNotEmpty());
	}

	@Test
	void duplicateSlugIs409AndIdempotentCreateReturnsTheSameCategory() throws Exception {
		var staffToken = newStaffToken();
		var slug = unique("dup");
		var first = create(staffToken, body(slug));
		post("/api/v1/admin/categories", body(slug), staffToken, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_slug"));
		var key = "cat-" + UUID.randomUUID();
		var other = body(unique("idem"));
		var a = post("/api/v1/admin/categories", other, staffToken, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		var b = post("/api/v1/admin/categories", other, staffToken, key).andReturn().getResponse().getContentAsString();
		assertThat(JSON.readTree(b).get("id")).isEqualTo(JSON.readTree(a).get("id"));
		assertThat(first.get("id")).isNotNull();
		post("/api/v1/admin/categories", body(unique("different")), staffToken, key).andExpect(status().isConflict());
	}

	@Test
	void updateRequiresTheCurrentVersionAndAStaleOneIsAConcurrentUpdate() throws Exception {
		var staffToken = newStaffToken();
		var slug = unique("upd");
		var created = create(staffToken, body(slug));
		var id = created.get("id").asString();
		put("/api/v1/admin/categories/" + id, body(slug), staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		var updated = put("/api/v1/admin/categories/" + id, body(slug, "version", created.get("version").asLong(), "name", Map.of("fr", "Nouveau", "ht", "Nouvo")), staffToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.name.fr").value("Nouveau")).andReturn().getResponse().getContentAsString();
		assertThat(JSON.readTree(updated).get("version").asLong()).isGreaterThan(created.get("version").asLong());
		put("/api/v1/admin/categories/" + id, body(slug, "version", created.get("version").asLong()), staffToken).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("concurrent_update"));
		var trail = audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("category", id);
		assertThat(trail).extracting(a -> a.getAction()).containsExactly("CATEGORY_CREATED", "CATEGORY_UPDATED");
	}

	/** The category as the API returned it, as an update body (ids kept), with {@code attributes} replaced. */
	private static Map<String, Object> asUpdate(JsonNode detail, List<Map<String, Object>> attributes) {
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("version", detail.get("version").asLong());
		m.put("slug", detail.get("slug").asString());
		m.put("name", Map.of("fr", "Catégorie", "ht", "Kategori"));
		m.put("attributes", attributes);
		return m;
	}

	private static Map<String, Object> attribute(JsonNode a, java.util.function.Predicate<String> keepOption) {
		var options = new ArrayList<Map<String, Object>>();
		for (var o : a.get("options")) {
			if (keepOption.test(o.get("code").asString())) {
				options.add(Map.of("id", o.get("id").asString(), "code", o.get("code").asString(), "label", Map.of("fr", o.get("code").asString(), "ht", o.get("code").asString())));
			}
		}
		var m = new java.util.LinkedHashMap<String, Object>();
		m.put("id", a.get("id").asString());
		m.put("key", a.get("key").asString());
		m.put("label", Map.of("fr", a.get("key").asString(), "ht", a.get("key").asString()));
		m.put("type", a.get("type").asString());
		m.put("required", a.get("required").asBoolean());
		m.put("filterable", a.get("filterable").asBoolean());
		m.put("variantAxis", a.get("variantAxis").asBoolean());
		m.put("options", options);
		return m;
	}

	@Test
	void whatProductsRelyOnCannotBeRemovedAndAnActiveProductBlocksDeactivation() throws Exception {
		var staffToken = newStaffToken();
		var fx = TestData.category();
		catalog.saveCategory(fx.category());
		var base = TestData.product(fx.category().id(), ProductStatus.ACTIVE, price(htg("10.00"), null, 1));
		var product = new com.anadepot.core.catalog.model.Product(base.id(), base.sku(), base.slug(), base.categoryId(), base.name(), base.description(), null, base.unit(),
				base.unitNote(), ProductStatus.ACTIVE, base.pricing(), List.of(TestData.variant(unique("V"), Map.of(fx.sizeAttribute(), fx.sizeM()), Map.of())),
				List.of(com.anadepot.core.catalog.model.AttributeValue.choice(fx.sizeAttribute(), fx.sizeS()), com.anadepot.core.catalog.model.AttributeValue.text(fx.materialAttribute(), "coton")),
				List.of(), TestData.T0, TestData.T0);
		catalog.saveProduct(product, staff);
		var id = fx.category().id();
		var path = "/api/v1/admin/categories/" + id;
		var detail = JSON.readTree(get(path, staffToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(detail.get("productCount").asLong()).isEqualTo(1);
		assertThat(detail.get("activeProductCount").asLong()).isEqualTo(1);
		var size = detail.get("attributes").get(0).get("key").asString().equals("size") ? detail.get("attributes").get(0) : detail.get("attributes").get(1);
		var material = detail.get("attributes").get(0) == size ? detail.get("attributes").get(1) : detail.get("attributes").get(0);
		assertThat(size.get("valueCount").asLong()).isEqualTo(1);
		assertThat(size.get("options").findValuesAsString("code")).contains("s", "m", "l");

		send(MockMvcRequestBuilders.patch(path + "/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), staffToken)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category_has_active_products"));
		put(path, asUpdate(detail, List.of(attribute(size, c -> true))), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attribute_in_use"));   // material dropped
		put(path, asUpdate(detail, List.of(attribute(size, c -> !c.equals("s")), attribute(material, c -> true))), staffToken)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("option_in_use"));             // option s is chosen by the product
		var unusedOptionRemoved = put(path, asUpdate(detail, List.of(attribute(size, c -> !c.equals("l")), attribute(material, c -> true))), staffToken).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		assertThat(JSON.readTree(unusedOptionRemoved).get("attributes").get(0).get("options")).hasSize(2);
		var after = JSON.readTree(unusedOptionRemoved);
		var sizeAfter = after.get("attributes").get(0).get("key").asString().equals("size") ? after.get("attributes").get(0) : after.get("attributes").get(1);
		var axisOff = attribute(sizeAfter, c -> true);
		axisOff.put("variantAxis", false);
		put(path, asUpdate(after, List.of(axisOff, attribute(material, c -> true))), staffToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("variant_axis_in_use"));
	}

	@Test
	void moveProductsBetweenCategoriesAllOrNothingAndAuditedAndOrderingNeedsEveryCategory() throws Exception {
		var staffToken = newStaffToken();
		var from = create(staffToken, body(unique("from"))).get("id").asString();
		var to = create(staffToken, body(unique("to"))).get("id").asString();
		var p1 = catalog.saveProduct(TestData.product(UUID.fromString(from), ProductStatus.DRAFT, price(htg("10.00"), null, 1)), staff);
		var p2 = catalog.saveProduct(TestData.product(UUID.fromString(from), ProductStatus.DRAFT, price(htg("11.00"), null, 1)), staff);
		get("/api/v1/admin/categories/" + from + "/products", staffToken).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));

		post("/api/v1/admin/categories/" + from + "/products/move", Map.of("toCategoryId", to, "productIds", List.of(p1.id(), UUID.randomUUID())), staffToken, null).andExpect(status().isNotFound());
		get("/api/v1/admin/categories/" + to + "/products", staffToken).andExpect(jsonPath("$.totalItems").value(0));      // nothing moved

		post("/api/v1/admin/categories/" + from + "/products/move", Map.of("toCategoryId", to, "productIds", List.of(p1.id(), p2.id())), staffToken, null)
				.andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(2));
		get("/api/v1/admin/categories/" + to + "/products", staffToken).andExpect(jsonPath("$.totalItems").value(2));
		post("/api/v1/admin/categories/" + from + "/products/move", Map.of("toCategoryId", from, "productIds", List.of(p1.id())), staffToken, null).andExpect(status().isUnprocessableContent());
		assertThat(audits.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("category", from)).extracting(a -> a.getAction()).contains("CATEGORY_PRODUCTS_MOVED");

		put("/api/v1/admin/categories/order", Map.of("categoryIds", List.of(from)), staffToken).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("order_incomplete"));
	}
}
