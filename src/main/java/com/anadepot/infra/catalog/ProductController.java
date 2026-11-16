package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductSearch;
import com.anadepot.core.catalog.model.SearchQuery;
import com.anadepot.core.catalog.model.ProductSort;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only catalog. Only ACTIVE products in ACTIVE categories are visible; anything else is a 404 (existence is not revealed).
 * One product, several currency prices. Query parameters are only parsed here; every rule lives in core ({@link ProductSearch}).
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController {

	private final CatalogService catalog;
	private final PublicCatalogAssembler assembler;

	ProductController(CatalogService catalog, PublicCatalogAssembler assembler) {
		this.catalog = catalog;
		this.assembler = assembler;
	}

	/** Browsing/filtering. With {@code q} it is the SAME full-text search as {@code /products/search} (one use case, no duplicated logic). */
	@GetMapping
	ProductDtos.PageResponse<ProductDtos.ProductResponse> list(@org.springframework.web.bind.annotation.ModelAttribute SearchParams params) {
		return search(params);
	}

	/**
	 * Full-text search + filters. Sorts: relevance (default with q), newest (default without q), name, price-asc, price-desc (price sorts and price
	 * ranges require a currency: HTG and USD amounts are never compared).
	 */
	@GetMapping("/search")
	ProductDtos.PageResponse<ProductDtos.ProductResponse> search(@org.springframework.web.bind.annotation.ModelAttribute SearchParams p) {
		var search = new ProductSearch(SearchQuery.of(p.getQ()), null, p.getCurrency() == null || p.getCurrency().isBlank() ? null : SupportedCurrency.parse(p.getCurrency()),
				p.isWholesale(), p.isInStock(), attributes(p.getAttr()), p.getBrand(), decimal("min", first(p.getMin(), p.getMinPrice())), decimal("max", first(p.getMax(), p.getMaxPrice())), sort(p.getSort()));
		return assembler.page(catalog.searchPublic(search, p.getCategory(), new PageRequest(p.getPage(), p.getSize())));
	}

	private static String first(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}

	/** Query parameters of the public search (bound by name; {@code min}/{@code max} are aliases of {@code minPrice}/{@code maxPrice}). */
	public static class SearchParams {
		private String q;
		private String category;
		private String currency;
		private boolean wholesale;
		private boolean inStock;
		private List<String> attr;
		private List<String> brand;
		private String min;
		private String max;
		private String minPrice;
		private String maxPrice;
		private String sort;
		private int page = 0;
		private int size = 20;

		public String getQ() { return q; }
		public void setQ(String q) { this.q = q; }
		public String getCategory() { return category; }
		public void setCategory(String category) { this.category = category; }
		public String getCurrency() { return currency; }
		public void setCurrency(String currency) { this.currency = currency; }
		public boolean isWholesale() { return wholesale; }
		public void setWholesale(boolean wholesale) { this.wholesale = wholesale; }
		public boolean isInStock() { return inStock; }
		public void setInStock(boolean inStock) { this.inStock = inStock; }
		public List<String> getAttr() { return attr; }
		public void setAttr(List<String> attr) { this.attr = attr; }
		public List<String> getBrand() { return brand; }
		public void setBrand(List<String> brand) { this.brand = brand; }
		public String getMin() { return min; }
		public void setMin(String min) { this.min = min; }
		public String getMax() { return max; }
		public void setMax(String max) { this.max = max; }
		public String getMinPrice() { return minPrice; }
		public void setMinPrice(String minPrice) { this.minPrice = minPrice; }
		public String getMaxPrice() { return maxPrice; }
		public void setMaxPrice(String maxPrice) { this.maxPrice = maxPrice; }
		public String getSort() { return sort; }
		public void setSort(String sort) { this.sort = sort; }
		public int getPage() { return page; }
		public void setPage(int page) { this.page = page; }
		public int getSize() { return size; }
		public void setSize(int size) { this.size = size; }
	}

	@GetMapping("/{idOrSlug}")
	ProductDtos.ProductResponse get(@PathVariable String idOrSlug) {
		return assembler.one(catalog.getPublicProduct(idOrSlug));
	}

	private static List<ProductSearch.AttributeFilter> attributes(List<String> raw) {
		var out = new ArrayList<ProductSearch.AttributeFilter>();
		if (raw != null) {
			if (raw.size() > 10) {
				throw new DomainRuleViolation("filter_invalid", "Too many attribute filters");
			}
			for (var entry : raw) {
				int i = entry.indexOf(':');
				if (i < 1 || i == entry.length() - 1) {
					throw new DomainRuleViolation("filter_invalid", "Attribute filters look like attr=key:optionCode");
				}
				out.add(new ProductSearch.AttributeFilter(entry.substring(0, i).strip(), entry.substring(i + 1).strip()));
			}
		}
		return out;
	}

	private static BigDecimal decimal(String name, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(raw.strip());
		} catch (NumberFormatException e) {
			throw new DomainRuleViolation("price_range_invalid", "'" + name + "' is not a number");
		}
	}

	private static ProductSort sort(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;   // the use case picks the default: relevance with a text, newest without
		}
		try {
			return ProductSort.valueOf(raw.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
		} catch (IllegalArgumentException e) {
			throw new DomainRuleViolation("sort_invalid", "sort must be one of relevance, newest, name, price-asc, price-desc");
		}
	}
}
