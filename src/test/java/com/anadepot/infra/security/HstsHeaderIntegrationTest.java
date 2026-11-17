package com.anadepot.infra.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.anadepot.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code ana.headers.hsts=true} (production, HTTPS-only): HSTS is sent even though a TLS-terminating proxy hands this application plain HTTP. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "ana.headers.hsts=true")
class HstsHeaderIntegrationTest extends AbstractIntegrationTest {

	@Autowired MockMvc mvc;

	@Test
	void strictTransportSecurityIsSentWhenEnabled() throws Exception {
		mvc.perform(get("/api/v1/categories")).andExpect(header().string("Strict-Transport-Security", "max-age=63072000 ; includeSubDomains"));
	}
}
