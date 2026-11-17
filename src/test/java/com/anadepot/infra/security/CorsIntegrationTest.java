package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The storefront (a different origin) may call the API from a browser; nobody else may, and wildcards do not exist. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "ana.cors.allowed-origins=https://shop.example.test, http://localhost:3000")
class CorsIntegrationTest extends AbstractIntegrationTest {

	private static final String SHOP = "https://shop.example.test";

	@Autowired MockMvc mvc;

	@Test
	void preflightFromAnAllowedOriginAdvertisesOnlyWhatTheApiUses() throws Exception {
		mvc.perform(options("/api/v1/reservations").header("Origin", SHOP).header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type,idempotency-key,authorization"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", SHOP))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	@Test
	void aSimpleCatalogReadFromAnAllowedOriginCarriesTheHeadersTheBrowserNeeds() throws Exception {
		var response = mvc.perform(get("/api/v1/categories").header("Origin", SHOP))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", SHOP))
				.andReturn().getResponse();
		assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Correlation-Id").contains("Retry-After");
	}

	@Test
	void anotherOriginIsRefusedAndGetsNoAllowOriginHeader() throws Exception {
		mvc.perform(options("/api/v1/reservations").header("Origin", "https://evil.example.test").header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		mvc.perform(get("/api/v1/categories").header("Origin", "https://evil.example.test"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void aPreflightNeverOpensTheAdminApiToAnonymousCalls() throws Exception {
		// The preflight itself only says "the browser may try"; the real request is still 401 without a staff token.
		mvc.perform(get("/api/v1/admin/products").header("Origin", SHOP)).andExpect(status().isUnauthorized());
	}

	@Test
	void wildcardOriginsAreRefusedAtStartup() {
		assertThatThrownBy(() -> new CorsProperties(List.of("*"))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CorsProperties(List.of("https://*.example.test"))).isInstanceOf(IllegalStateException.class);
		assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
	}

	@Test
	void withNoOriginConfiguredNoBrowserOriginIsAllowed() {
		var source = SecurityConfig.corsSource(new CorsProperties(List.of()));
		assertThat(source.getCorsConfigurations()).isEmpty();
	}
}
