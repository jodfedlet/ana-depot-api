package com.anadepot.infra.catalog;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@TestPropertySource(properties = {"ana.rate-limit.limits[catalog.search].max-requests=4", "ana.rate-limit.limits[catalog.search].window=PT10M",
		"ana.rate-limit.limits[admin.reindex].max-requests=2", "ana.rate-limit.limits[admin.reindex].window=PT10M"})
class CatalogSearchRateLimitIntegrationTest extends ApiTestSupport {

	private org.springframework.test.web.servlet.ResultActions from(String ip, String path) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.get(path).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}));
	}

	@Test
	void bothSearchEntryPointsShareTheStrictSearchLimit_whileBrowsingIsUnaffected() throws Exception {
		var ip = "203.0.113." + (1 + (int) (Math.random() * 250));
		from(ip, "/api/v1/products?q=abc").andExpect(status().isOk());
		from(ip, "/api/v1/products/search?q=abc").andExpect(status().isOk());
		from(ip, "/api/v1/products/search").andExpect(status().isOk());         // even without a text, /search is the search operation
		from(ip, "/api/v1/products?q=abc").andExpect(status().isOk());
		from(ip, "/api/v1/products/search?q=abc").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
		from(ip, "/api/v1/products?q=abc").andExpect(status().isTooManyRequests());
		from(ip, "/api/v1/products").andExpect(status().isOk());                // plain browsing uses catalog.read
	}

	@Test
	void reindexIsStrictlyRateLimited() throws Exception {
		var admin = newStaffToken();
		post("/api/v1/admin/catalog/search/reindex", java.util.Map.of(), admin, null).andExpect(status().isOk());
		post("/api/v1/admin/catalog/search/reindex", java.util.Map.of(), admin, null).andExpect(status().isOk());
		post("/api/v1/admin/catalog/search/reindex", java.util.Map.of(), admin, null).andExpect(status().isTooManyRequests());
	}
}
