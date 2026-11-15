package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.AppliedPrice;
import com.anadepot.core.catalog.model.AttributeType;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.repository.CategoryRepository;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.HashSet;
import java.util.UUID;

public class CatalogServiceImpl implements CatalogService {

	private final ProductRepository products;
	private final CategoryRepository categories;
	private final TransactionBoundary tx;

	public CatalogServiceImpl(ProductRepository products, CategoryRepository categories, TransactionBoundary tx) {
		this.products = products;
		this.categories = categories;
		this.tx = tx;
	}

	@Override
	public Category saveCategory(Category category) {
		return tx.inTransaction(() -> categories.save(category));
	}

	@Override
	public Product saveProduct(Product product) {
		return tx.inTransaction(() -> {
			var category = categories.findById(product.categoryId()).orElseThrow(() -> new NotFoundException("Category", product.categoryId()));
			if (product.sku() != null && products.skuInUse(product.sku(), product.id())) {
				throw new DomainRuleViolation("duplicate_sku", "SKU already used by another product: " + product.sku());
			}
			var seen = new HashSet<UUID>();
			for (var value : product.attributes()) {
				var def = category.attribute(value.attributeId());
				if (def == null) {
					throw new DomainRuleViolation("attribute_not_in_category", "Attribute does not belong to the product's category: " + value.attributeId());
				}
				if (!seen.add(def.id())) {
					throw new DomainRuleViolation("attribute_value_duplicate", "Two values for attribute " + def.key());
				}
				boolean ok = switch (def.type()) {
					case CHOICE -> value.optionId() != null && def.option(value.optionId()) != null;
					case TEXT -> value.text() != null;
					case NUMBER -> value.number() != null;
				};
				if (!ok) {
					throw new DomainRuleViolation("attribute_value_invalid", "Value does not match attribute " + def.key() + " (" + def.type() + ")");
				}
			}
			for (var variant : product.variants()) {
				for (var axis : variant.axisValues().entrySet()) {
					var def = category.attribute(axis.getKey());
					if (def == null || def.type() != AttributeType.CHOICE || !def.variantAxis() || def.option(axis.getValue()) == null) {
						throw new DomainRuleViolation("variant_axis_invalid", "Variant " + variant.sku() + " uses an attribute that is not a variant axis of the category");
					}
				}
			}
			if (product.status() == ProductStatus.ACTIVE) {
				product.requirePublishable(category);
			}
			return products.save(product);
		});
	}

	@Override
	public Product getProduct(UUID id) {
		return products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
	}

	@Override
	public PageResult<Product> listProducts(PageRequest request, ProductStatus statusOrNull) {
		return products.findPage(request, statusOrNull);
	}

	@Override
	public AppliedPrice quote(UUID productId, UUID variantId, SupportedCurrency currency, int quantity) {
		return getProduct(productId).quote(variantId, currency, quantity);
	}

	@Override
	public String describeVariant(Product product, UUID variantId, String lang) {
		if (variantId == null) {
			return "";
		}
		var variant = product.variant(variantId);
		var category = categories.findById(product.categoryId()).orElse(null);
		if (variant == null || category == null) {
			return variant == null ? "" : variant.sku();
		}
		var parts = new java.util.ArrayList<String>();
		for (var def : category.attributes()) {
			var optionId = variant.axisValues().get(def.id());
			if (optionId != null && def.option(optionId) != null) {
				parts.add(def.label().in(lang) + ": " + def.option(optionId).label().in(lang));
			}
		}
		return parts.isEmpty() ? variant.sku() : String.join(" \u00b7 ", parts);
	}
}
