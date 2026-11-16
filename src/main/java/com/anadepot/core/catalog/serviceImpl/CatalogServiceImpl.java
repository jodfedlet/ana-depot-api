package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.AppliedPrice;
import com.anadepot.core.catalog.model.AttributeType;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.repository.CategoryRepository;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

public class CatalogServiceImpl implements CatalogService {

	private final ProductRepository products;
	private final CategoryRepository categories;
	private final TransactionBoundary tx;
	private final AuditRecorder audit;
	private final com.anadepot.core.catalog.repository.ProductSearchIndex searchIndex;

	public CatalogServiceImpl(ProductRepository products, CategoryRepository categories, TransactionBoundary tx, AuditRecorder audit,
			com.anadepot.core.catalog.repository.ProductSearchIndex searchIndex) {
		this.audit = audit;
		this.searchIndex = searchIndex;
		this.products = products;
		this.categories = categories;
		this.tx = tx;
	}

	@Override
	public Category saveCategory(Category category) {
		return tx.inTransaction(() -> {
			var saved = categories.save(category);
			// a renamed category or changed attribute labels change the searchable text of every product in it: same transaction
			for (var id : products.idsByCategory(saved.id())) {
				products.findById(id).ifPresent(p -> searchIndex.index(p.id(), com.anadepot.core.catalog.model.SearchDocument.of(p, saved)));
			}
			return saved;
		});
	}

	@Override
	public Product saveProduct(Product product, Actor actor) {
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
			var previous = products.findById(product.id()).orElse(null);
			var saved = products.save(product);
			searchIndex.index(saved.id(), com.anadepot.core.catalog.model.SearchDocument.of(saved, category));
			auditChanges(previous, saved, actor);
			return saved;
		});
	}

	/** Price and status changes are sensitive: who changed what, from which values to which, in one audit entry each. */
	private void auditChanges(Product previous, Product current, Actor actor) {
		var who = actor == null ? Actor.system() : actor;
		for (var price : current.pricing().all()) {
			var before = previous == null ? null : previous.pricing().price(price.currency()).orElse(null);
			var now = describe(price);
			if (before == null || !describe(before).equals(now)) {
				audit.record(AuditEvent.of("PRODUCT_PRICE_CHANGED", "product", current.id(), who,
						before == null ? java.util.Map.of() : java.util.Map.of("currency", price.currency().name(), "price", describe(before)),
						java.util.Map.of("currency", price.currency().name(), "price", now), null));
			}
		}
		if (previous != null) {
			for (var before : previous.pricing().all()) {
				if (current.pricing().price(before.currency()).isEmpty()) {
					audit.record(AuditEvent.of("PRODUCT_PRICE_REMOVED", "product", current.id(), who, java.util.Map.of("currency", before.currency().name(), "price", describe(before)), java.util.Map.of(), null));
				}
			}
		}
		if (previous == null || previous.status() != current.status()) {
			audit.record(AuditEvent.of("PRODUCT_STATUS_CHANGED", "product", current.id(), who,
					previous == null ? java.util.Map.of() : java.util.Map.of("status", previous.status().name()), java.util.Map.of("status", current.status().name()), null));
		}
	}

	private static String describe(com.anadepot.core.catalog.model.ProductPrice p) {
		var tiers = p.tiers().stream().map(t -> t.minQuantity() + "+=" + t.unitPrice().amount().toPlainString()).toList();
		return "retail=" + p.retail().amount().toPlainString() + ";sale=" + (p.sale() == null ? "-" : p.sale().amount().toPlainString())
				+ ";min=" + p.minOrderQuantity() + ";wholesale=" + p.wholesaleEnabled() + tiers + ";active=" + p.active();
	}

	@Override
	public int reindexSearch(Actor actor) {
		int indexed = 0;
		for (int page = 0; ; page++) {
			final int current = page;
			var done = tx.inTransaction(() -> {
				var result = products.findPage(new PageRequest(current, 100), null);
				var cats = categories.findAllById(result.items().stream().map(Product::categoryId).collect(java.util.stream.Collectors.toSet()));
				result.items().forEach(p -> searchIndex.index(p.id(), com.anadepot.core.catalog.model.SearchDocument.of(p, cats.get(p.categoryId()))));
				return new int[] {result.items().size(), (current + 1) * 100 >= result.totalItems() ? 1 : 0};
			});
			indexed += done[0];
			if (done[1] == 1) {
				break;
			}
		}
		final int total = indexed;
		tx.inTransaction(() -> audit.record(AuditEvent.of("SEARCH_REINDEXED", "catalog", "search", actor == null ? Actor.system() : actor, java.util.Map.of(), java.util.Map.of("indexed", total), null)));
		return indexed;
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

	@Override
	public PageResult<Product> searchPublic(com.anadepot.core.catalog.model.ProductSearch search, String categorySlug, PageRequest request) {
		var effective = search;
		if (categorySlug != null && !categorySlug.isBlank()) {
			if (categorySlug.length() > 100 || categorySlug.chars().anyMatch(Character::isISOControl)) {
				return new PageResult<>(java.util.List.of(), request.page(), request.size(), 0);   // cannot be a real slug
			}
			var category = categories.findActiveBySlug(categorySlug.strip());
			if (category.isEmpty()) {
				return new PageResult<>(java.util.List.of(), request.page(), request.size(), 0);
			}
			effective = search.inCategory(category.get().id());
		}
		return products.searchPublic(effective, request);
	}

	@Override
	public Product getPublicProduct(String idOrSlug) {
		Optional<Product> found;
		try {
			found = products.findById(UUID.fromString(idOrSlug));
		} catch (IllegalArgumentException notAnId) {
			found = products.findBySlug(idOrSlug);
		}
		var product = found.filter(p -> p.status() == ProductStatus.ACTIVE).orElseThrow(() -> new NotFoundException("Product", idOrSlug));
		categories.findById(product.categoryId()).filter(c -> c.active()).orElseThrow(() -> new NotFoundException("Product", idOrSlug));
		return product;
	}

	@Override
	public java.util.List<Category> listPublicCategories() {
		return categories.findActive();
	}

	@Override
	public Category getPublicCategory(String slug) {
		return categories.findActiveBySlug(slug).orElseThrow(() -> new NotFoundException("Category", slug));
	}

	@Override
	public java.util.Map<UUID, Long> publicProductCounts() {
		return categories.activeProductCounts();
	}

	@Override
	public java.util.Map<UUID, Category> categoriesById(java.util.Collection<UUID> ids) {
		return categories.findAllById(ids);
	}
}
