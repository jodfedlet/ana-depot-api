package com.anadepot.infra.reservation;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.infra.ApiTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request builders and helpers shared by the reservation / account HTTP tests. */
public abstract class ReservationHttpSupport extends ApiTestSupport {

	protected static final JsonMapper JSON = JsonMapper.builder().build();

	protected static Map<String, Object> line(Product p, int quantity, String currency) {
		var m = new LinkedHashMap<String, Object>();
		m.put("productId", p.id());
		m.put("quantity", quantity);
		m.put("currency", currency);
		return m;
	}

	@SafeVarargs
	protected static Map<String, Object> request(String phone, Map<String, Object>... lines) {
		var contact = new LinkedHashMap<String, Object>();
		contact.put("firstName", "Marie");
		contact.put("lastName", "Exemple");
		contact.put("dialCode", "+509");
		contact.put("phone", phone);
		var body = new LinkedHashMap<String, Object>();
		body.put("lines", new ArrayList<>(List.of(lines)));
		body.put("contact", contact);
		body.put("locale", "fr");
		body.put("acknowledgedTerms", true);
		return body;
	}

	/** A phone number nobody else in the shared test database uses. */
	protected static String freshPhone() {
		return "3" + String.format("%07d", (int) (Math.random() * 10_000_000));
	}

	protected static JsonNode body(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	protected JsonNode reserve(Map<String, Object> request, String bearer) throws Exception {
		var result = post("/api/v1/reservations", request, bearer, "key-" + UUID.randomUUID()).andReturn();
		if (result.getResponse().getStatus() != 201) {
			throw new AssertionError("expected 201 but got " + result.getResponse().getStatus() + ": " + result.getResponse().getContentAsString());
		}
		return body(result);
	}

	/** Problem body without the per-request correlation id: what a caller can compare across requests. */
	protected static String stable(MvcResult result) throws Exception {
		var node = (tools.jackson.databind.node.ObjectNode) body(result);
		node.remove("correlationId");
		return result.getResponse().getStatus() + " " + node;
	}
}
