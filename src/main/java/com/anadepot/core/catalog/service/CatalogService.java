package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.AppliedPrice;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
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
	Product saveProduct(Product product);

	Product getProduct(UUID id);

	PageResult<Product> listProducts(PageRequest request, ProductStatus statusOrNull);

	/** Human-readable variant description from the category's attribute labels, e.g. "Size: 42 · Colour: Black". Empty for a product without variants. */
	String describeVariant(Product product, UUID variantId, String lang);

	/** Unit price of a product/variant in a currency for a quantity (no stock check; see ReservationService). */
	AppliedPrice quote(UUID productId, UUID variantId, SupportedCurrency currency, int quantity);
}
