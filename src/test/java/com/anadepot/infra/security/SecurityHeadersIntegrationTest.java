package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.anadepot.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every response of the API (success, authentication failure, validation failure, unknown path) carries the same defensive headers, and no response of an
 * authenticated or personal endpoint may be stored by a browser or shared cache. HSTS is off by default (plain HTTP in tests and behind a proxy).
 */
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTest extends AbstractIntegrationTest {

	@Autowired MockMvc mvc;

	private static void assertDefensiveHeaders(ResultActions result) throws Exception {
		result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
				.andExpect(header().exists("Permissions-Policy"));
	}

	private static String cacheControl(ResultActions result) {
		return result.andReturn().getResponse().getHeader("Cache-Control");
	}

	@Test
	void aPublicReadCarriesTheDefensiveHeaders() throws Exception {
		assertDefensiveHeaders(mvc.perform(get("/api/v1/categories")));
	}

	@Test
	void anAuthenticationFailureCarriesThemAndIsNeverStored() throws Exception {
		var result = mvc.perform(get("/api/v1/me")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
		assertDefensiveHeaders(result);
		assertThat(cacheControl(result)).contains("no-store");
		var admin = mvc.perform(get("/api/v1/admin/products")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
		assertDefensiveHeaders(admin);
		assertThat(cacheControl(admin)).contains("no-store");
	}

	@Test
	void aValidationFailureAndAnUnknownPathCarryThem() throws Exception {
		assertDefensiveHeaders(mvc.perform(post("/api/v1/reservations/lookup").contentType(MediaType.APPLICATION_JSON).content("{")));
		assertDefensiveHeaders(mvc.perform(get("/api/v1/does-not-exist")));
	}

	@Test
	void everyResponseIsStoredByNobodyByDefault() throws Exception {
		// A personal answer (reservation lookup) must never sit in a browser or proxy cache: the API sets no Cache-Control of its own, Spring Security's no-store applies.
		var result = mvc.perform(post("/api/v1/reservations/lookup").contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"ANA-NOPE00\",\"phone\":\"0000\"}"));
		assertThat(cacheControl(result)).contains("no-store");
	}

	@Test
	void hstsIsOffUntilTheDeploymentIsHttpsOnly() throws Exception {
		mvc.perform(get("/api/v1/categories")).andExpect(header().doesNotExist("Strict-Transport-Security"));
	}
}
