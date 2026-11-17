package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.AdminProductQuery;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductAdminView;
import com.anadepot.core.catalog.model.ProductDraft;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.repository.CategoryRepository;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.catalog.service.ProductAdminService;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ProductAdminServiceImpl implements ProductAdminService {

	private final ProductRepository products;
	private final CategoryRepository categories;
	private final CatalogService catalog;
	private final InventoryService inventory;
	private final AuditRecorder audit;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;

	public ProductAdminServiceImpl(ProductRepository products, CategoryRepository categories, CatalogService catalog, InventoryService inventory, AuditRecorder audit,
			IdempotencyService idempotency, TransactionBoundary tx, Clock clock) {
		this.products = products;
		this.categories = categories;
		this.catalog = catalog;
		this.inventory = inventory;
		this.audit = audit;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public PageResult<ProductAdminView> list(AdminProductQuery query, PageRequest page) {
		return tx.inTransaction(() -> {
			var result = products.searchAdmin(query, page);
			var stock = inventory.positionsOf(result.items().stream().map(Product::id).toList());
			return new PageResult<>(result.items().stream().map(p -> view(p, stock.getOrDefault(p.id(), List.of()))).toList(), result.page(), result.size(), result.totalItems());
		});
	}

	@Override
	public ProductAdminView get(UUID id) {
		return tx.inTransaction(() -> view(find(id)));
	}

	@Override
	public ProductAdminView create(ProductDraft draft, Actor actor, String idempotencyKey) {
		var fingerprint = Fingerprint.of(draft);
		return idempotency.run(idempotencyKey, "staff:" + actorId(actor), "product.create", fingerprint, () -> tx.inTransaction(() -> {
			var now = clock.instant();
			var status = createStatus(draft.status());
			var id = UUID.randomUUID();
			var slug = uniqueSlug(draft.slugOrDerived(), draft.slug() != null && !draft.slug().isBlank());
			var product = draft.toProduct(id, null, slug, status, now);
			checkReferences(product, id);
			var saved = catalog.saveProduct(product, actor);
			audit.record(AuditEvent.of("PRODUCT_CREATED", "product", saved.id(), actor, Map.of(), summary(saved), null));
			return saved.id();
		}), this::get);
	}

	@Override
	public ProductAdminView update(UUID id, long expectedVersion, ProductDraft draft, Actor actor) {
		return tx.inTransaction(() -> {
			var before = find(id);
			var meta = products.meta(id).orElseThrow(() -> new NotFoundException("Product", id));
			if (meta.version() != expectedVersion) {
				throw new DomainRuleViolation("concurrent_update", "The product was changed by someone else (version " + meta.version() + "). Reload it and try again.");
			}
			var requestedSlug = draft.slug() == null || draft.slug().isBlank() ? before.slug() : draft.slug().strip();
			if (!requestedSlug.equals(before.slug()) && products.slugInUse(requestedSlug, id)) {
				throw new DomainRuleViolation("duplicate_slug", "Another product already uses the slug " + requestedSlug);
			}
			var next = draft.toProduct(id, before, requestedSlug, before.status(), clock.instant());
			checkReferences(next, id);
			var keptVariants = next.variants().stream().map(v -> v.id()).collect(Collectors.toSet());
			for (var used : products.variantIdsInUse(id)) {
				if (!keptVariants.contains(used)) {
					throw new DomainRuleViolation("variant_in_use", "A variant that has stock or reservations cannot be removed: deactivate it instead");
				}
			}
			var saved = catalog.saveProduct(next, actor);
			audit.record(AuditEvent.of("PRODUCT_UPDATED", "product", id, actor, summary(before), summary(saved), null));
			return view(find(id));
		});
	}

	@Override
	public ProductAdminView setStatus(UUID id, ProductStatus target, String reason, Actor actor) {
		return tx.inTransaction(() -> {
			var before = find(id);
			if (before.status() == target) {
				return view(before);
			}
			boolean allowed = (before.status() == ProductStatus.DRAFT && target == ProductStatus.ACTIVE) || (before.status() == ProductStatus.ACTIVE && target == ProductStatus.INACTIVE)
					|| (before.status() == ProductStatus.INACTIVE && target == ProductStatus.ACTIVE);
			if (!allowed) {
				throw new DomainRuleViolation("invalid_status", "A product cannot go from " + before.status() + " to " + target);
			}
			if (target == ProductStatus.ACTIVE) {
				requirePublishableTexts(before);
			}
			var saved = catalog.saveProduct(before.withStatus(target, clock.instant()), actor);   // publishing re-validates the product; the status change is audited there
			if (reason != null && !reason.isBlank()) {
				audit.record(AuditEvent.of(target == ProductStatus.ACTIVE ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", "product", id, actor, Map.of("status", before.status().name()),
						Map.of("status", target.name()), reason.strip()));
			}
			return view(find(saved.id()));
		});
	}

	@Override
	public void delete(UUID id, Actor actor) {
		tx.inTransaction(() -> {
			var product = find(id);
			if (product.status() != ProductStatus.DRAFT) {
				throw new DomainRuleViolation("product_in_use", "Only a draft can be deleted; deactivate a product that was published");
			}
			if (!product.images().isEmpty() || products.hasInventory(id) || products.hasReservationLines(id)) {
				throw new DomainRuleViolation("product_in_use", "This product has images, a stock record or reservations: remove them first or keep the product");
			}
			products.delete(id);
			audit.record(AuditEvent.of("PRODUCT_DELETED", "product", id, actor, summary(product), Map.of(), null));
			return null;
		});
	}

	// ---- helpers ----

	private Product find(UUID id) {
		return products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
	}

	private ProductAdminView view(Product p) {
		return view(p, inventory.positionsOf(List.of(p.id())).getOrDefault(p.id(), List.of()));
	}

	private ProductAdminView view(Product p, List<com.anadepot.core.inventory.model.InventoryItem> stock) {
		var meta = products.meta(p.id()).orElseThrow();
		return new ProductAdminView(p, meta.version(), stock);
	}

	private static ProductStatus createStatus(String text) {
		if (text == null || text.isBlank()) {
			return ProductStatus.DRAFT;
		}
		try {
			var status = ProductStatus.valueOf(text.strip().toUpperCase(java.util.Locale.ROOT));
			if (status == ProductStatus.INACTIVE) {
				throw new FieldValidationException(List.of(new FieldIssue("status", "invalid")));
			}
			return status;
		} catch (IllegalArgumentException e) {
			throw new FieldValidationException(List.of(new FieldIssue("status", "invalid")));
		}
	}

	/** SKU and slug uniqueness, the category, and (for a published product) both languages of the name. */
	private void checkReferences(Product product, UUID id) {
		if (categories.findById(product.categoryId()).isEmpty()) {
			throw new NotFoundException("Category", product.categoryId());
		}
		if (product.sku() != null && products.skuTaken(product.sku(), id)) {
			throw new DomainRuleViolation("duplicate_sku", "SKU already used: " + product.sku());
		}
		for (var v : product.variants()) {
			if (products.skuTaken(v.sku(), id)) {
				throw new DomainRuleViolation("duplicate_sku", "SKU already used: " + v.sku());
			}
		}
		if (product.status() == ProductStatus.ACTIVE) {
			requirePublishableTexts(product);
		}
	}

	/** Customer-facing products are written in both languages (French and Haitian Creole). */
	private static void requirePublishableTexts(Product product) {
		var issues = new ArrayList<FieldIssue>();
		for (var lang : List.of("fr", "ht")) {
			if (product.name().in(lang) == null || product.name().values().get(lang) == null || product.name().values().get(lang).isBlank()) {
				issues.add(new FieldIssue("name." + lang, "required_to_publish"));
			}
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
	}

	private String uniqueSlug(String base, boolean explicit) {
		if (base == null || base.isBlank()) {
			return null;   // the draft reports "slug required"
		}
		if (explicit) {
			if (products.slugInUse(base, null)) {
				throw new DomainRuleViolation("duplicate_slug", "Another product already uses the slug " + base);
			}
			return base;
		}
		var candidate = base;
		for (int n = 2; products.slugInUse(candidate, null); n++) {
			var suffix = "-" + n;
			candidate = (base.length() + suffix.length() > 120 ? base.substring(0, 120 - suffix.length()) : base) + suffix;
		}
		return candidate;
	}

	private static String actorId(Actor actor) {
		return actor == null || actor.userId() == null ? "system" : actor.userId().toString();
	}

	private static Map<String, Object> summary(Product p) {
		var map = new LinkedHashMap<String, Object>();
		map.put("sku", p.sku());
		map.put("slug", p.slug());
		map.put("categoryId", p.categoryId().toString());
		map.put("name", p.name().values());
		map.put("brand", p.brand());
		map.put("unit", p.unit().name());
		map.put("status", p.status().name());
		map.put("currencies", p.pricing().all().stream().map(x -> x.currency().name() + (x.active() ? "" : ":inactive")).sorted().toList());
		map.put("variants", p.variants().stream().map(v -> v.sku() + (v.active() ? "" : ":inactive")).toList());
		map.put("attributeValues", p.attributes().size());
		return map;
	}
}
