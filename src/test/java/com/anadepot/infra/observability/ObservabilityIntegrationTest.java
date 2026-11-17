package com.anadepot.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anadepot.infra.ApiTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Correlation ids, log hygiene, health groups and the metrics an operator needs, against the REAL filter chain and PostgreSQL.
 * (Token signing keys are local: this is not a Supabase integration test.)
 */
class ObservabilityIntegrationTest extends ApiTestSupport {

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private Logger root;

	@BeforeEach
	void captureLogs() {
		root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		root.setLevel(Level.DEBUG);
		logs.start();
		root.addAppender(logs);
	}

	@AfterEach
	void stopCapturing() {
		root.detachAppender(logs);
		logs.stop();
		root.setLevel(Level.INFO);
	}

	/** Everything that was logged, as a reviewer would grep it: message, formatted arguments, MDC and any throwable text. */
	private String allLoggedText() {
		var sb = new StringBuilder();
		for (var e : List.copyOf(logs.list)) {
			sb.append(e.getFormattedMessage()).append(' ').append(e.getMDCPropertyMap()).append(' ').append(e.getMarkerList());
			if (e.getThrowableProxy() != null) sb.append(' ').append(e.getThrowableProxy().getMessage());
			sb.append('\n');
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ correlation

	@Test
	void aSuppliedCorrelationIdIsEchoedLoggedAndReturnedInTheProblemBody() throws Exception {
		var id = "corr-" + UUID.randomUUID();
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/products/" + UUID.randomUUID()).header("X-Correlation-Id", id))
				.andExpect(status().isNotFound())
				.andExpect(header().string("X-Correlation-Id", id))
				.andExpect(jsonPath("$.correlationId").value(id));
		var requestLine = logs.list.stream().filter(e -> e.getLoggerName().equals("anadepot.request")).filter(e -> id.equals(e.getMDCPropertyMap().get("correlationId"))).findFirst();
		assertThat(requestLine).as("the one-line request log carries the id").isPresent();
		assertThat(requestLine.get().getFormattedMessage()).contains("method=GET", "status=404", "durationMs=");
	}

	@Test
	void aMalformedCorrelationIdIsReplacedNeverEchoedIntoLogsOrHeaders() throws Exception {
		var evil = "x\r\nSet-Cookie: a=b";
		var result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/products/" + UUID.randomUUID()).header("X-Correlation-Id", evil)).andExpect(status().isNotFound()).andReturn();
		var echoed = result.getResponse().getHeader("X-Correlation-Id");
		assertThat(echoed).isNotNull().matches("[0-9a-f-]{36}").doesNotContain("Set-Cookie");
		assertThat(allLoggedText()).doesNotContain("Set-Cookie");
	}

	@Test
	void errorsFromAuthorizationCarryTheCorrelationIdToo() throws Exception {
		var id = "corr-" + UUID.randomUUID();
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/products").header("X-Correlation-Id", id)).andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-Id", id));
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/products").header("X-Correlation-Id", id).header("Authorization", "Bearer " + token(UUID.randomUUID()))).andExpect(status().isForbidden()).andExpect(header().string("X-Correlation-Id", id));
	}

	// ------------------------------------------------------------------ log hygiene

	@Test
	void logsNeverContainTokensQueryStringsPhonesOrReferencesFromRequests() throws Exception {
		var secretToken = newStaffToken();
		var phone = "37001234";
		var reference = "ANA-ZZZ" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/products").param("q", "secret-search-" + phone)).andExpect(status().isOk());
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/products").header("Authorization", "Bearer " + secretToken)).andExpect(status().isOk());
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations/lookup").contentType("application/json").content("{\"reference\":\"" + reference + "\",\"phone\":\"" + phone + "\"}")).andExpect(status().isNotFound());
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/reservations/lookup").contentType("application/json").content("{not json")).andExpect(status().is4xxClientError());

		var text = allLoggedText();
		assertThat(text).as("the bearer token / its signature").doesNotContain(secretToken).doesNotContain(secretToken.substring(secretToken.lastIndexOf('.') + 1));
		assertThat(text).as("the query string").doesNotContain("secret-search").doesNotContain("q=");
		assertThat(text).as("personal data of the lookup body").doesNotContain(phone).doesNotContain(reference);
		assertThat(text).doesNotContain("Authorization").doesNotContain("password");
	}

	// ------------------------------------------------------------------ health

	@Test
	void livenessAndReadinessArePublicAndExposeNoDetails() throws Exception {
		mvc.perform(MockMvcRequestBuilders.get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void theDependenciesGroupChecksTheDatabaseAndStorageButAnUnconfiguredStoreIsNotDown() throws Exception {
		// the test context has no object storage configured: UNKNOWN for storage, UP for the database, so the group is UP
		mvc.perform(MockMvcRequestBuilders.get("/actuator/health/dependencies")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	// ------------------------------------------------------------------ metrics

	@Test
	void metricsAreStaffOnlyAndExposeLatencyPoolOutboxAndLimiterFigures() throws Exception {
		mvc.perform(MockMvcRequestBuilders.get("/api/v1/products")).andExpect(status().isOk());
		mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics")).andExpect(status().isUnauthorized());
		mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics").header("Authorization", "Bearer " + token(UUID.randomUUID()))).andExpect(status().isForbidden());
		mvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus")).andExpect(status().is4xxClientError());       // never public on the API port

		var staff = "Bearer " + newStaffToken();
		for (var name : List.of("http.server.requests", "hikaricp.connections.active", "hikaricp.connections.max", "hikaricp.connections.pending", "anadepot.outbox.pending", "anadepot.outbox.dead", "anadepot.outbox.oldest.undelivered.age")) {
			assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics/" + name).header("Authorization", staff)).andReturn().getResponse().getStatus()).as("metric %s is exposed", name).isEqualTo(200);
		}
		// latency is recorded per route template and status, so alerts can use error rate and percentiles without high-cardinality labels
		mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics/http.server.requests").header("Authorization", staff).param("tag", "status:200"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.measurements[?(@.statistic=='COUNT')].value").isNotEmpty());
	}
}
