package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.CategoryAdminView;
import com.anadepot.core.catalog.model.CategoryDraft;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.repository.CategoryRepository;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.catalog.service.CategoryAdminService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CategoryAdminServiceImpl implements CategoryAdminService {

	private final CategoryRepository categories;
	private final ProductRepository products;
	private final CatalogService catalog;
	private final AuditRecorder audit;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;

	public CategoryAdminServiceImpl(CategoryRepository categories, ProductRepository products, CatalogService catalog, AuditRecorder audit, IdempotencyService idempotency,
			TransactionBoundary tx, Clock clock) {
		this.categories = categories;
		this.products = products;
		this.catalog = catalog;
		this.audit = audit;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public PageResult<CategoryAdminView> list(PageRequest page, Boolean activeOrNull, String textOrNull) {
		return tx.inTransaction(() -> {
			var result = categories.findPage(page, activeOrNull, textOrNull == null || textOrNull.isBlank() ? null : textOrNull.strip());
			var ids = result.items().stream().map(Category::id).toList();
			var all = categories.productCounts(ids);
			var active = categories.activeProductCounts();
			return new PageResult<>(result.items().stream().map(c -> view(c, all.getOrDefault(c.id(), 0L), active.getOrDefault(c.id(), 0L), false)).toList(),
					result.page(), result.size(), result.totalItems());
		});
	}

	@Override
	public CategoryAdminView get(UUID id) {
		return tx.inTransaction(() -> detail(find(id)));
	}

	@Override
	public CategoryAdminView create(CategoryDraft draft, Actor actor, String idempotencyKey) {
		var fingerprint = Fingerprint.of(draft);
		return idempotency.run(idempotencyKey, scope(actor), "category.create", fingerprint, () -> tx.inTransaction(() -> {
			var id = UUID.randomUUID();
			var category = draft.toCategory(id, null, categories.maxDisplayOrder() + 1);
			if (categories.slugInUse(category.slug(), null)) {
				throw new DomainRuleViolation("duplicate_slug", "Another category already uses the slug " + category.slug());
			}
			var saved = catalog.saveCategory(category);
			audit.record(AuditEvent.of("CATEGORY_CREATED", "category", saved.id(), actor, Map.of(), summary(saved), null));
			return saved.id();
		}), id -> get(id));
	}

	@Override
	public CategoryAdminView update(UUID id, long expectedVersion, CategoryDraft draft, Actor actor) {
		return tx.inTransaction(() -> {
			var before = find(id);
			requireVersion(id, expectedVersion);
			var next = draft.toCategory(id, before, before.displayOrder());
			if (!next.slug().equals(before.slug()) && categories.slugInUse(next.slug(), id)) {
				throw new DomainRuleViolation("duplicate_slug", "Another category already uses the slug " + next.slug());
			}
			if (before.active() && !next.active() && activeProducts(id) > 0) {
				throw new DomainRuleViolation("category_has_active_products", "Deactivate or move the active products of this category first");
			}
			protect(before, next);
			var saved = catalog.saveCategory(next);
			audit.record(AuditEvent.of("CATEGORY_UPDATED", "category", id, actor, summary(before), summary(saved), null));
			return detail(find(id));
		});
	}

	@Override
	public CategoryAdminView setActive(UUID id, boolean active, String reason, Actor actor) {
		return tx.inTransaction(() -> {
			var before = find(id);
			if (before.active() == active) {
				return detail(before);
			}
			if (!active && activeProducts(id) > 0) {
				throw new DomainRuleViolation("category_has_active_products", "Deactivate or move the active products of this category first");
			}
			var next = new Category(before.id(), before.slug(), before.name(), before.description(), before.imageUrl(), active, before.displayOrder(), before.attributes());
			catalog.saveCategory(next);
			audit.record(AuditEvent.of(active ? "CATEGORY_ACTIVATED" : "CATEGORY_DEACTIVATED", "category", id, actor, Map.of("active", before.active()), Map.of("active", active),
					reason == null || reason.isBlank() ? null : reason.strip()));
			return detail(find(id));
		});
	}

	@Override
	public List<CategoryAdminView> reorder(List<UUID> orderedIds, Actor actor) {
		return tx.inTransaction(() -> {
			var current = categories.allIdsInOrder();
			if (orderedIds == null || orderedIds.size() != current.size() || !new HashSet<>(orderedIds).equals(new HashSet<>(current))) {
				throw new DomainRuleViolation("order_incomplete", "The new order must list every category exactly once");
			}
			categories.applyOrder(orderedIds);
			audit.record(AuditEvent.of("CATEGORIES_REORDERED", "category", "all", actor, Map.of("order", current.stream().map(UUID::toString).toList()),
					Map.of("order", orderedIds.stream().map(UUID::toString).toList()), null));
			var all = categories.findAllById(orderedIds);
			var counts = categories.productCounts(orderedIds);
			var active = categories.activeProductCounts();
			return orderedIds.stream().map(all::get).map(c -> view(c, counts.getOrDefault(c.id(), 0L), active.getOrDefault(c.id(), 0L), false)).toList();
		});
	}

	@Override
	public PageResult<Product> products(UUID categoryId, PageRequest page) {
		return tx.inTransaction(() -> {
			find(categoryId);
			return products.findPageByCategory(categoryId, page);
		});
	}

	@Override
	public int moveProducts(UUID fromCategoryId, UUID toCategoryId, List<UUID> productIds, Actor actor) {
		if (productIds == null || productIds.isEmpty() || productIds.size() > 200 || new HashSet<>(productIds).size() != productIds.size()) {
			throw new DomainRuleViolation("products_invalid", "Give between 1 and 200 distinct product ids");
		}
		if (fromCategoryId.equals(toCategoryId)) {
			throw new DomainRuleViolation("same_category", "The products are already in that category");
		}
		return tx.inTransaction(() -> {
			find(fromCategoryId);
			var target = find(toCategoryId);
			if (!target.active()) {
				throw new DomainRuleViolation("category_inactive", "Products cannot be moved into an inactive category");
			}
			var now = clock.instant();
			for (var productId : productIds) {
				var p = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
				if (!p.categoryId().equals(fromCategoryId)) {
					throw new DomainRuleViolation("product_not_in_category", "Product " + productId + " is not in the source category");
				}
				catalog.saveProduct(p.withCategory(toCategoryId, now), actor);   // full product validation against the target category
			}
			audit.record(AuditEvent.of("CATEGORY_PRODUCTS_MOVED", "category", fromCategoryId, actor, Map.of("category", fromCategoryId.toString()),
					Map.of("category", toCategoryId.toString(), "products", productIds.stream().map(UUID::toString).toList()), null));
			return productIds.size();
		});
	}

	// ---- protection of what the catalog relies on ----

	/** A change must not strand products, variants or required values that already exist. */
	private void protect(Category before, Category next) {
		var kept = new HashMap<UUID, AttributeDefinition>();
		next.attributes().forEach(a -> kept.put(a.id(), a));
		for (var old : before.attributes()) {
			var now = kept.get(old.id());
			if (now == null) {
				if (categories.attributeValueCount(old.id()) > 0 || categories.variantAxisUseCount(old.id()) > 0) {
					throw new DomainRuleViolation("attribute_in_use", "Attribute " + old.key() + " is used by products; remove its values first");
				}
				continue;
			}
			if (now.type() != old.type() && categories.attributeValueCount(old.id()) > 0) {
				throw new DomainRuleViolation("attribute_type_locked", "Attribute " + old.key() + " holds values: its type cannot change");
			}
			if (old.variantAxis() && !now.variantAxis() && categories.variantAxisUseCount(old.id()) > 0) {
				throw new DomainRuleViolation("variant_axis_in_use", "Attribute " + old.key() + " is the axis of existing variants");
			}
			if (!old.required() && now.required() && now.active() && categories.activeProductsWithoutValue(before.id(), old.id()) > 0) {
				throw new DomainRuleViolation("attribute_required_violation", "Active products have no value for " + old.key() + "; they would stop being publishable");
			}
			var keptOptions = new HashSet<UUID>();
			now.options().forEach(o -> keptOptions.add(o.id()));
			for (AttributeOption option : old.options()) {
				if (!keptOptions.contains(option.id()) && categories.optionUseCount(option.id()) > 0) {
					throw new DomainRuleViolation("option_in_use", "Option " + option.code() + " of " + old.key() + " is chosen by products or variants");
				}
			}
		}
		for (var added : next.attributes()) {
			if (before.attribute(added.id()) == null && added.required() && added.active() && activeProducts(before.id()) > 0) {
				throw new DomainRuleViolation("attribute_required_violation", "Active products would lack the new required attribute " + added.key());
			}
		}
	}

	private long activeProducts(UUID categoryId) {
		return categories.activeProductCounts().getOrDefault(categoryId, 0L);
	}

	private Category find(UUID id) {
		return categories.findById(id).orElseThrow(() -> new NotFoundException("Category", id));
	}

	private void requireVersion(UUID id, long expected) {
		var meta = categories.meta(id).orElseThrow(() -> new NotFoundException("Category", id));
		if (meta.version() != expected) {
			throw new DomainRuleViolation("concurrent_update", "The category was changed by someone else (version " + meta.version() + "). Reload it and try again.");
		}
	}

	private CategoryAdminView detail(Category c) {
		var counts = categories.productCounts(List.of(c.id()));
		var active = categories.activeProductCounts();
		return view(c, counts.getOrDefault(c.id(), 0L), active.getOrDefault(c.id(), 0L), true);
	}

	private CategoryAdminView view(Category c, long productCount, long activeCount, boolean withUsage) {
		var meta = categories.meta(c.id()).orElseThrow();
		var attributeCounts = new LinkedHashMap<UUID, Long>();
		var optionCounts = new LinkedHashMap<UUID, Long>();
		if (withUsage) {
			for (var a : c.attributes()) {
				attributeCounts.put(a.id(), categories.attributeValueCount(a.id()));
				for (var o : a.options()) {
					optionCounts.put(o.id(), categories.optionUseCount(o.id()));
				}
			}
		}
		return new CategoryAdminView(c, meta.version(), meta.createdAt(), meta.updatedAt(), productCount, activeCount, attributeCounts, optionCounts);
	}

	private static Map<String, Object> summary(Category c) {
		var map = new LinkedHashMap<String, Object>();
		map.put("slug", c.slug());
		map.put("name", c.name().values());
		map.put("active", c.active());
		map.put("displayOrder", c.displayOrder());
		map.put("attributes", c.attributes().stream().map(a -> a.key() + ":" + a.type() + (a.required() ? ":required" : "") + (a.filterable() ? ":filterable" : "") + (a.variantAxis() ? ":axis" : "")
				+ (a.options().isEmpty() ? "" : "[" + a.options().stream().map(AttributeOption::code).toList() + "]")).toList());
		return map;
	}

	private static String scope(Actor actor) {
		return "staff:" + (actor == null || actor.userId() == null ? "system" : actor.userId());
	}
}
