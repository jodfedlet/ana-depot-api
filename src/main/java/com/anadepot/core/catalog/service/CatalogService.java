package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.AppliedPrice;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.UUID;

public interface CatalogService {

	Category saveCategory(Category category);

	/**
	 * Stores a product after the cross-aggregate checks that no single object can do: the category exists, every
	 * attribute value belongs to that category and matches its type/options, the SKU is unique, and an ACTIVE product
	 * is publishable.
	 */
	Product saveProduct(Product product, Actor actor);

	Product getProduct(UUID id);

	/** Public catalog search (active products in active categories only). {@code categorySlug} null = all; an unknown/inactive slug yields an empty page. */
	PageResult<Product> searchPublic(com.anadepot.core.catalog.model.ProductSearch search, String categorySlug, PageRequest request);

	/** One ACTIVE product by id (UUID text) or slug; draft/inactive/unknown/in an inactive category => NotFoundException. */
	Product getPublicProduct(String idOrSlug);

	java.util.List<Category> listPublicCategories();

	Category getPublicCategory(String slug);

	/** Active product count per category id. */
	java.util.Map<UUID, Long> publicProductCounts();

	java.util.Map<UUID, Category> categoriesById(java.util.Collection<UUID> ids);

	/**
	 * Rebuilds the search index of EVERY product from the current catalog (repair/backfill). Idempotent; audited; one transaction per page.
	 * @return number of products indexed
	 */
	int reindexSearch(Actor actor);

	PageResult<Product> listProducts(PageRequest request, ProductStatus statusOrNull);

	/** Human-readable variant description from the category's attribute labels, e.g. "Size: 42 · Colour: Black". Empty for a product without variants. */
	String describeVariant(Product product, UUID variantId, String lang);

	/** Unit price of a product/variant in a currency for a quantity (no stock check; see ReservationService). */
	AppliedPrice quote(UUID productId, UUID variantId, SupportedCurrency currency, int quantity);
}
