package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A public catalog query. Prices are only ever compared INSIDE one currency: a price range or a price sort without a currency is
 * refused (HTG and USD amounts are never compared or ordered together). {@code currency} also means "priced in that currency".
 */
public record ProductSearch(SearchQuery query, UUID categoryId, SupportedCurrency currency, boolean wholesaleOnly, boolean inStockOnly,
		List<AttributeFilter> attributes, List<String> brands, BigDecimal minPrice, BigDecimal maxPrice, ProductSort sort) {

	public record AttributeFilter(String key, String optionCode) {
	}

	public ProductSearch {
		query = query == null ? SearchQuery.empty() : query;
		attributes = attributes == null ? List.of() : List.copyOf(attributes);
		brands = brands == null ? List.of() : List.copyOf(brands);
		if (brands.stream().anyMatch(b -> b.length() > 100 || hasControl(b)) || attributes.stream().anyMatch(a -> a.key().length() > 60 || a.optionCode().length() > 60 || hasControl(a.key()) || hasControl(a.optionCode()))) {
			throw new DomainRuleViolation("filter_invalid", "A filter value is not valid");
		}
		// default order: relevance when there is a text, else newest first
		sort = sort == null ? (query.isEmpty() ? ProductSort.NEWEST : ProductSort.RELEVANCE) : (sort == ProductSort.RELEVANCE && query.isEmpty() ? ProductSort.NEWEST : sort);
		boolean priceBased = minPrice != null || maxPrice != null || sort == ProductSort.PRICE_ASC || sort == ProductSort.PRICE_DESC;
		if (priceBased && currency == null) {
			throw new DomainRuleViolation("currency_required", "A price filter or price sort needs a currency (HTG or USD): amounts of different currencies are never compared");
		}
		if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0) || (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)) {
			throw new DomainRuleViolation("price_range_invalid", "The price range is not valid");
		}
	}

	private static boolean hasControl(String s) {
		return s.chars().anyMatch(Character::isISOControl);
	}

	public static ProductSearch all() {
		return new ProductSearch(null, null, null, false, false, null, null, null, null, null);
	}

	public ProductSearch inCategory(UUID id) {
		return new ProductSearch(query, id, currency, wholesaleOnly, inStockOnly, attributes, brands, minPrice, maxPrice, sort);
	}
}
