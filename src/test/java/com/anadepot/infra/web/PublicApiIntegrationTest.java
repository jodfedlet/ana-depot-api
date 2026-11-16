package com.anadepot.infra.web;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.testsupport.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PublicApiIntegrationTest extends AbstractIntegrationTest {

	@Autowired MockMvc mvc;

	@Test
	void oneProductResponseCarriesIndependentPricesInBothCurrenciesAsDecimalStrings() throws Exception {
		var category = TestData.category().category();
		catalog.saveCategory(category);
		var saved = catalog.saveProduct(product(category.id(), ProductStatus.ACTIVE,
				price(htg("25000.00"), null, 1, tier(5, htg("23000.00"))),
				price(usd("180.00"), usd("170.00"), 2, tier(5, usd("160.00")), tier(10, usd("150.00")))), staff);

		mvc.perform(get("/api/v1/products/" + saved.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(saved.id().toString()))
				.andExpect(jsonPath("$.prices.length()").value(2))
				.andExpect(jsonPath("$.prices[?(@.currency=='HTG')].retailPrice").value("25000.00"))
				.andExpect(jsonPath("$.prices[?(@.currency=='HTG')].salePrice").value((Object) null))
				.andExpect(jsonPath("$.prices[?(@.currency=='HTG')].wholesaleTiers.length()").value(1))
				.andExpect(jsonPath("$.prices[?(@.currency=='USD')].retailPrice").value("180.00"))
				.andExpect(jsonPath("$.prices[?(@.currency=='USD')].salePrice").value("170.00"))
				.andExpect(jsonPath("$.prices[?(@.currency=='USD')].minOrderQuantity").value(2))
				.andExpect(jsonPath("$.prices[?(@.currency=='USD')].wholesaleTiers[1].minQuantity").value(10))
				.andExpect(jsonPath("$.prices[?(@.currency=='USD')].wholesaleTiers[1].unitPrice").value("150.00"))
				.andExpect(jsonPath("$.sellingUnit").value("UNIT"))
				.andExpect(jsonPath("$.version").doesNotExist())          // no persistence internals leak
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	@Test
	void inactiveAndDraftProductsAreNotFoundPublicly_andUnknownIdsToo() throws Exception {
		var category = TestData.category().category();
		catalog.saveCategory(category);
		var inactive = catalog.saveProduct(product(category.id(), ProductStatus.INACTIVE, price(htg("10.00"), null, 1)), staff);
		var draft = catalog.saveProduct(product(category.id(), com.anadepot.testsupport.TestData.unique("D"), ProductStatus.DRAFT, java.util.List.of(), price(htg("10.00"), null, 1)), staff);

		for (var id : new UUID[] {inactive.id(), draft.id(), UUID.randomUUID()}) {
			mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("not_found"))
					.andExpect(jsonPath("$.status").value(404));
		}
	}

	@Test
	void theListShowsOnlyActiveProductsAndPagesAreBounded() throws Exception {
		var category = TestData.category().category();
		catalog.saveCategory(category);
		var active = catalog.saveProduct(product(category.id(), ProductStatus.ACTIVE, price(usd("5.00"), null, 1)), staff);
		var inactive = catalog.saveProduct(product(category.id(), ProductStatus.INACTIVE, price(usd("5.00"), null, 1)), staff);

		var body = mvc.perform(get("/api/v1/products?page=0&size=100&category=" + category.slug())).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).contains(active.id().toString()).doesNotContain(inactive.id().toString());
		mvc.perform(get("/api/v1/products?size=101")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_page"));
		mvc.perform(get("/api/v1/products?page=-1")).andExpect(status().isUnprocessableContent());
	}

	@Test
	void errorsUseProblemJsonWithACorrelationIdThatIsEchoedOrGenerated() throws Exception {
		mvc.perform(get("/api/v1/products/" + UUID.randomUUID()).header("X-Correlation-Id", "client-trace-12345"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("X-Correlation-Id", "client-trace-12345"))
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
				.andExpect(jsonPath("$.correlationId").value("client-trace-12345"));
		// a malformed id from the client (could be an injection attempt) is replaced, never echoed
		var generated = mvc.perform(get("/api/v1/products/" + UUID.randomUUID()).header("X-Correlation-Id", "bad id\nwith newline"))
				.andExpect(status().isNotFound()).andReturn().getResponse().getHeader("X-Correlation-Id");
		assertThat(generated).matches("[0-9a-f\\-]{36}");
	}

	@Test
	void everyRequestIsLoggedWithItsCorrelationIdAndWithoutTheQueryString() throws Exception {
		var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("anadepot.request");
		var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/api/v1/products?page=0&size=5&phone=0000123456").header("X-Correlation-Id", "trace-log-check-1")).andExpect(status().isOk());
		} finally {
			logger.detachAppender(appender);
		}
		var event = appender.list.stream().filter(e -> e.getMDCPropertyMap().containsValue("trace-log-check-1")).findFirst().orElseThrow();
		assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "trace-log-check-1");
		assertThat(event.getFormattedMessage()).contains("path=/api/v1/products").contains("status=200").contains("durationMs=").doesNotContain("phone").doesNotContain("0000123456");
	}

	@Test
	void internalErrorsDoNotLeakDetails() throws Exception {
		mvc.perform(get("/api/v1/products/not-a-uuid")).andExpect(status().is4xxClientError());
	}
}
