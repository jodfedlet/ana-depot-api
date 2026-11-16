package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductSearch;
import com.anadepot.core.catalog.model.SearchQuery;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPT-IN performance measurement (not part of the normal suite):
 * {@code mvn test -Dtest=CatalogSearchPerformanceTest -Dsearch.perf=true [-Dsearch.perf.products=20000]}.
 * Generates a catalog, runs each scenario through the real use case (query + page load + stock), prints p50/p95 and EXPLAIN (ANALYZE, BUFFERS),
 * and writes target/search-perf.txt. Numbers depend on the machine; they are measured on a Testcontainers PostgreSQL 17, not on Supabase.
 */
@EnabledIfSystemProperty(named = "search.perf", matches = "true")
class CatalogSearchPerformanceTest extends AbstractIntegrationTest {

	@Autowired ProductSearchNative search;
	@Autowired InventoryRepository inventoryRepository;
	@Autowired CatalogService catalogService;
	@Autowired PlatformTransactionManager tm;
	@PersistenceContext EntityManager em;

	private static double percentile(long[] sorted, double p) {
		return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] / 1_000_000.0;
	}

	@Test
	void measureTypicalSearches() throws Exception {
		int products = Integer.getInteger("search.perf.products", 20_000);
		var tx = new TransactionTemplate(tm);
		var generator = new CatalogGenerator(catalogService, inventoryRepository, search, em, tx, 42L);
		long t0 = System.nanoTime();
		generator.products(products, generator.categories(12), "PRF");
		tx.executeWithoutResult(s -> em.createNativeQuery("analyze products").executeUpdate());
		long generationMs = (System.nanoTime() - t0) / 1_000_000;

		var scenarios = new LinkedHashMap<String, ProductSearch>();
		scenarios.put("single term (brouette)", new ProductSearch(SearchQuery.of("brouette"), null, null, false, false, null, null, null, null, null));
		scenarios.put("prefix (matel)", new ProductSearch(SearchQuery.of("matel"), null, null, false, false, null, null, null, null, null));
		scenarios.put("three terms (table moderne bois)", new ProductSearch(SearchQuery.of("table moderne bois"), null, null, false, false, null, null, null, null, null));
		scenarios.put("Creole (chèz anpile)", new ProductSearch(SearchQuery.of("chèz anpile"), null, null, false, false, null, null, null, null, null));
		scenarios.put("single term + USD price range + in stock", new ProductSearch(SearchQuery.of("table"), null, SupportedCurrency.USD, false, true, null, null, new java.math.BigDecimal("50"), new java.math.BigDecimal("300"), null));
		scenarios.put("text + wholesale HTG + sort price asc", new ProductSearch(SearchQuery.of("lampe"), null, SupportedCurrency.HTG, true, false, null, null, null, null, com.anadepot.core.catalog.model.ProductSort.PRICE_ASC));
		scenarios.put("empty text listing (newest)", new ProductSearch(SearchQuery.empty(), null, null, false, false, null, null, null, null, null));
		scenarios.put("empty text + in stock + USD", new ProductSearch(SearchQuery.empty(), null, SupportedCurrency.USD, false, true, null, null, null, null, null));

		var report = new StringBuilder("Catalog: " + products + " products, generation+indexing " + generationMs + " ms\n");
		for (var e : scenarios.entrySet()) {
			for (int i = 0; i < 20; i++) {
				catalogService.searchPublic(e.getValue(), null, new PageRequest(0, 24));    // warm up
			}
			var times = new long[200];
			long total = 0;
			for (int i = 0; i < times.length; i++) {
				long s = System.nanoTime();
				var page = catalogService.searchPublic(e.getValue(), null, new PageRequest(0, 24));
				times[i] = System.nanoTime() - s;
				total = page.totalItems();
			}
			Arrays.sort(times);
			report.append(String.format("%-48s matches=%-6d p50=%.1f ms  p95=%.1f ms  max=%.1f ms%n", e.getKey(), total, percentile(times, 0.5), percentile(times, 0.95), percentile(times, 1.0)));
		}
		report.append("\nEXPLAIN (ANALYZE, BUFFERS) single term:\n").append(String.join("\n", search.explain(SearchQuery.of("brouette").tsQuery(), true)));
		report.append("\n\nEXPLAIN (ANALYZE, BUFFERS) three terms:\n").append(String.join("\n", search.explain(SearchQuery.of("table moderne bois").tsQuery(), true)));
		System.out.println(report);
		Files.writeString(Path.of("target/search-perf.txt"), report.toString());
	}
}
