package com.anadepot.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.infra.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * With a private management port configured, a scraper reads health and Prometheus figures WITHOUT a token on that port, while the public API port
 * still refuses {@code /actuator/prometheus} and keeps metrics staff-only. Real sockets, real filter chains.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"management.server.port=0", "management.endpoints.web.exposure.include=health,metrics,prometheus"})
@AutoConfigureMetrics   // Spring Boot tests switch metric EXPORT off by default; this one tests the export endpoint
class ManagementPortIntegrationTest extends AbstractIntegrationTest {

	@LocalServerPort int apiPort;
	@LocalManagementPort int managementPort;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> get(int port, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void theManagementPortServesPrometheusFiguresAndHealthWithoutATokenAndNothingElse() throws Exception {
		assertThat(managementPort).isNotEqualTo(apiPort);
		get(apiPort, "/api/v1/products");   // some traffic to measure
		var prometheus = get(managementPort, "/actuator/prometheus");
		assertThat(prometheus.statusCode()).as("prometheus on the management port: %s", prometheus.body()).isEqualTo(200);
		assertThat(prometheus.body()).contains("http_server_requests_seconds_count", "hikaricp_connections_max", "anadepot_outbox_pending", "anadepot_outbox_dead", "anadepot_outbox_oldest_undelivered_age_seconds");
		assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).as("readiness on the management port").isEqualTo(200);
		assertThat(get(managementPort, "/api/v1/products").statusCode()).as("the management port is not a second way into the API").isIn(401, 403, 404);
		assertThat(get(managementPort, "/api/v1/admin/products").statusCode()).isIn(401, 403, 404);
	}

	@Test
	void thePublicApiPortStillRefusesPrometheusAndStaysTokenProtected() throws Exception {
		assertThat(get(apiPort, "/actuator/prometheus").statusCode()).isIn(401, 403, 404);
		assertThat(get(apiPort, "/actuator/metrics").statusCode()).isIn(401, 403, 404);
		assertThat(get(apiPort, "/api/v1/admin/products").statusCode()).isEqualTo(401);
	}
}
