package com.anadepot.core.inventory.serviceImpl;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.InventoryPositionView;
import com.anadepot.core.inventory.model.InventoryQuery;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.repository.InventoryRepository;
import com.anadepot.core.inventory.service.InventoryAdminService;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InventoryAdminServiceImpl implements InventoryAdminService {

	/** A sanity bound, not a business limit: it stops a typo (an extra zero, a pasted number) from creating a nonsensical position. */
	static final int MAX_QUANTITY = 10_000_000;

	private final InventoryRepository inventory;
	private final InventoryService stock;
	private final ProductRepository products;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;

	public InventoryAdminServiceImpl(InventoryRepository inventory, InventoryService stock, ProductRepository products, AuditRecorder audit, TransactionBoundary tx) {
		this.inventory = inventory;
		this.stock = stock;
		this.products = products;
		this.audit = audit;
		this.tx = tx;
	}

	@Override
	public PageResult<InventoryPositionView> list(InventoryQuery query, PageRequest page) {
		return tx.inTransaction(() -> {
			var result = inventory.findPage(query, page);
			var byId = products.findAllById(result.items().stream().map(InventoryItem::productId).distinct().toList());
			var views = new ArrayList<InventoryPositionView>();
			for (var item : result.items()) {
				views.add(view(item, byId.get(item.productId())));
			}
			return new PageResult<>(views, result.page(), result.size(), result.totalItems());
		});
	}

	@Override
	public InventoryPositionView get(UUID id) {
		return tx.inTransaction(() -> view(find(id)));
	}

	@Override
	public InventoryPositionView register(UUID productId, UUID variantId, boolean tracked, int initialOnHand, Integer lowStockThreshold, Actor actor) {
		var issues = new ArrayList<FieldIssue>();
		if (productId == null) {
			issues.add(new FieldIssue("productId", "required"));
		}
		if (initialOnHand < 0 || initialOnHand > MAX_QUANTITY) {
			issues.add(new FieldIssue("initialOnHand", "invalid"));
		}
		if (!tracked && initialOnHand != 0) {
			issues.add(new FieldIssue("initialOnHand", "must_be_zero_when_untracked"));
		}
		if (lowStockThreshold != null && (lowStockThreshold < 0 || lowStockThreshold > MAX_QUANTITY)) {
			issues.add(new FieldIssue("lowStockThreshold", "invalid"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return tx.inTransaction(() -> {
			var product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
			if (variantId == null && !product.variants().isEmpty()) {
				throw new DomainRuleViolation("variant_required", "This product has variants: stock is registered per variant");
			}
			if (variantId != null && product.variants().stream().noneMatch(v -> v.id().equals(variantId))) {
				throw new NotFoundException("Variant", variantId);
			}
			var item = stock.register(productId, variantId, tracked, initialOnHand, lowStockThreshold, actor == null ? null : actor.userId());
			audit.record(AuditEvent.of("INVENTORY_REGISTERED", "inventory_item", item.id(), actor, Map.of(),
					Map.of("productId", productId.toString(), "variantId", variantId == null ? "" : variantId.toString(), "tracked", tracked, "onHand", item.onHand()), null));
			return view(item, product);
		});
	}

	@Override
	public StockMovement adjust(UUID id, MovementType type, int quantity, StockReason reason, String note, Actor actor, String idempotencyKey) {
		var issues = new ArrayList<FieldIssue>();
		if (type == null || (type != MovementType.ADD && type != MovementType.REDUCE && type != MovementType.RECOUNT)) {
			issues.add(new FieldIssue("type", "invalid"));
		}
		if (quantity < 0 || quantity > MAX_QUANTITY) {
			issues.add(new FieldIssue("quantity", "invalid"));
		}
		if (reason == null || reason == StockReason.SALE) {
			issues.add(new FieldIssue("reason", reason == null ? "required" : "invalid"));
		}
		if (note != null && note.length() > 500) {
			issues.add(new FieldIssue("note", "too_long"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var item = tx.inTransaction(() -> find(id));
		return stock.adjust(item.productId(), item.variantId(), type, quantity, reason, note, actor == null ? null : actor.userId(), idempotencyKey);
	}

	@Override
	public InventoryPositionView updateThreshold(UUID id, long expectedVersion, Integer lowStockThreshold, Actor actor) {
		if (lowStockThreshold != null && (lowStockThreshold < 0 || lowStockThreshold > MAX_QUANTITY)) {
			throw new FieldValidationException(List.of(new FieldIssue("lowStockThreshold", "invalid")));
		}
		return tx.inTransaction(() -> {
			var before = find(id);
			var item = inventory.findForUpdate(before.productId(), before.variantId()).orElseThrow(() -> new NotFoundException("Inventory item", id));
			var meta = inventory.meta(id).orElseThrow();
			if (meta.version() != expectedVersion) {
				throw new DomainRuleViolation("concurrent_update", "The stock position was changed by someone else (version " + meta.version() + "). Reload it and try again.");
			}
			var next = inventory.save(new InventoryItem(item.id(), item.productId(), item.variantId(), item.tracked(), lowStockThreshold, item.onHand(), item.reserved()));
			var changed = new java.util.HashMap<String, Object>();
			changed.put("lowStockThreshold", lowStockThreshold);
			var previous = new java.util.HashMap<String, Object>();
			previous.put("lowStockThreshold", item.lowStockThreshold());
			audit.record(AuditEvent.of("INVENTORY_THRESHOLD_CHANGED", "inventory_item", id, actor, previous, changed, null));
			return view(next);
		});
	}

	@Override
	public PageResult<StockMovement> movements(UUID id, PageRequest page) {
		return tx.inTransaction(() -> {
			find(id);
			return inventory.movementsPage(id, page);
		});
	}

	private InventoryItem find(UUID id) {
		return inventory.findById(id).orElseThrow(() -> new NotFoundException("Inventory item", id));
	}

	private InventoryPositionView view(InventoryItem item) {
		return view(item, products.findById(item.productId()).orElseThrow());
	}

	private InventoryPositionView view(InventoryItem item, Product product) {
		var meta = inventory.meta(item.id()).orElseThrow();
		return new InventoryPositionView(item, meta.version(), meta.updatedAt(), product);
	}
}
