package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * N+1 guard. A list endpoint must issue a FIXED number of SQL statements however many rows it returns: the count is measured with Hibernate's statistics at two data sizes and must
 * not grow. (Statement counts, not timings: they are stable on any machine.) A failure here means a lazy association is being loaded row by row.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountIntegrationTest extends ApiTestSupport {

	@Autowired EntityManagerFactory emf;

	private long statements(String path, String bearer) throws Exception {
		var stats = emf.unwrap(SessionFactory.class).getStatistics();
		get(path, bearer).andReturn();                  // warm-up: first call fills caches (token keys, metadata) and is not representative
		stats.clear();
		var status = get(path, bearer).andReturn().getResponse().getStatus();
		assertThat(status).as(path).isEqualTo(200);
		return stats.getPrepareStatementCount();
	}

	private void grow(int reservationsToAdd) {
		ensurePickupAvailable();
		var product = stockedMattress(10_000);
		for (int i = 0; i < reservationsToAdd; i++) {
			submit(key(), line(product, 1, com.anadepot.core.shared.money.SupportedCurrency.HTG), "+509 3" + String.format("%07d", (long) (Math.random() * 9_999_999)));
		}
	}

	@Test
	void listEndpointsIssueAFixedNumberOfStatementsWhateverTheNumberOfRows() throws Exception {
		var staff = newStaffToken();
		var paths = new String[] { "/api/v1/products?size=20", "/api/v1/categories", "/api/v1/products/search?q=mattress&size=20", "/api/v1/admin/reservations?size=20", "/api/v1/admin/customers?size=20",
				"/api/v1/admin/products?size=20", "/api/v1/admin/inventory?size=20", "/api/v1/admin/payments?size=20" };
		grow(4);
		var small = new long[paths.length];
		for (int i = 0; i < paths.length; i++) {
			small[i] = statements(paths[i], paths[i].startsWith("/api/v1/admin") ? staff : null);
		}
		grow(14);   // a page of 20 is now full of rows: any per-row query would multiply the count
		for (int i = 0; i < paths.length; i++) {
			long large = statements(paths[i], paths[i].startsWith("/api/v1/admin") ? staff : null);
			assertThat(large).as("%s: statements with more rows (was %d)", paths[i], small[i]).isLessThanOrEqualTo(small[i] + 2);
		}
	}

	@Test
	void aCustomersReservationListAlsoStaysFlat() throws Exception {
		var user = java.util.UUID.randomUUID();
		var token = token(user);
		ensurePickupAvailable();
		var product = stockedMattress(10_000);
		for (int i = 0; i < 3; i++) {
			post("/api/v1/reservations", java.util.Map.of("lines", java.util.List.of(java.util.Map.of("productId", product.id().toString(), "quantity", 1, "currency", "HTG")),
					"contact", java.util.Map.of("firstName", "Q", "lastName", "Count", "dialCode", "+509", "phone", "3" + String.format("%07d", (long) (Math.random() * 9_999_999))),
					"locale", "fr", "acknowledgedTerms", true), token, key());
		}
		long few = statements("/api/v1/me/reservations?size=20", token);
		for (int i = 0; i < 12; i++) {
			post("/api/v1/reservations", java.util.Map.of("lines", java.util.List.of(java.util.Map.of("productId", product.id().toString(), "quantity", 1, "currency", "HTG")),
					"contact", java.util.Map.of("firstName", "Q", "lastName", "Count", "dialCode", "+509", "phone", "3" + String.format("%07d", (long) (Math.random() * 9_999_999))),
					"locale", "fr", "acknowledgedTerms", true), token, key());
		}
		long many = statements("/api/v1/me/reservations?size=20", token);
		assertThat(many).isLessThanOrEqualTo(few + 2);
	}
}
