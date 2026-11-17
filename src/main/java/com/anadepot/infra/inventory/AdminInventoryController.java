package com.anadepot.infra.inventory;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.InventoryPositionView;
import com.anadepot.core.inventory.model.InventoryQuery;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.core.inventory.service.InventoryAdminService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.infra.web.PageResponse;
import com.anadepot.infra.security.Actors;
import com.anadepot.infra.web.QueryParams;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff stock management ({@code /api/v1/admin/inventory}). Stock is shared by HTG and USD. Parsing and shaping only. */
@RestController
@RequestMapping("/api/v1/admin/inventory")
class AdminInventoryController {

	record RegisterRequest(UUID productId, UUID variantId, Boolean tracked, Integer initialOnHand, Integer lowStockThreshold) {
	}

	record AdjustRequest(String type, Integer quantity, String reason, String note) {
	}

	record ThresholdRequest(Long version, Integer lowStockThreshold) {
	}

	record Position(UUID id, UUID productId, UUID variantId, String productSku, Map<String, String> productName, String productStatus, UUID categoryId, String variantSku, boolean tracked,
			Integer onHand, Integer reserved, Integer available, Integer lowStockThreshold, String state, long version, Instant updatedAt) {
	}

	record Movement(UUID id, UUID inventoryItemId, String type, int delta, int balanceAfter, String reason, UUID reservationId, String note, Instant at, UUID recordedBy) {
	}

	private final InventoryAdminService inventory;

	AdminInventoryController(InventoryAdminService inventory) {
		this.inventory = inventory;
	}

	@GetMapping
	PageResponse<Position> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID productId,
			@RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String state, @RequestParam(required = false) String q, @RequestParam(required = false) String sort) {
		var query = new InventoryQuery(productId, categoryId, QueryParams.enumOf(InventoryItem.StockState.class, state, "state"), q, QueryParams.enumOf(InventoryQuery.Sort.class, sort, "sort"));
		var result = inventory.list(query, new PageRequest(page, size));
		return PageResponse.of(result, AdminInventoryController::position);
	}

	@GetMapping("/{id}")
	Position get(@PathVariable UUID id) {
		return position(inventory.get(id));
	}

	@PostMapping
	ResponseEntity<Position> register(@RequestBody RegisterRequest body, Authentication authentication) {
		if (body == null || body.productId() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("productId", "required")));
		}
		boolean tracked = body.tracked() == null || body.tracked();
		int initial = body.initialOnHand() == null ? 0 : body.initialOnHand();
		return ResponseEntity.status(HttpStatus.CREATED).body(position(inventory.register(body.productId(), body.variantId(), tracked, initial, body.lowStockThreshold(), Actors.staff(authentication))));
	}

	@PostMapping("/{id}/adjustments")
	ResponseEntity<Movement> adjust(@PathVariable UUID id, @RequestBody AdjustRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.type() == null) {
			issues.add(new FieldIssue("type", "required"));
		}
		if (body == null || body.quantity() == null) {
			issues.add(new FieldIssue("quantity", "required"));
		}
		if (body == null || body.reason() == null) {
			issues.add(new FieldIssue("reason", "required"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var movement = inventory.adjust(id, QueryParams.enumOf(MovementType.class, body.type(), "type"), body.quantity(), QueryParams.enumOf(StockReason.class, body.reason(), "reason"), body.note(),
				Actors.staff(authentication), idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(movement(movement));
	}

	@PatchMapping("/{id}/threshold")
	Position threshold(@PathVariable UUID id, @RequestBody ThresholdRequest body, Authentication authentication) {
		if (body == null || body.version() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("version", "required")));
		}
		return position(inventory.updateThreshold(id, body.version(), body.lowStockThreshold(), Actors.staff(authentication)));
	}

	@GetMapping("/{id}/movements")
	PageResponse<Movement> movements(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var result = inventory.movements(id, new PageRequest(page, size));
		return PageResponse.of(result, AdminInventoryController::movement);
	}

	private static Position position(InventoryPositionView v) {
		var i = v.item();
		var p = v.product();
		return new Position(i.id(), i.productId(), i.variantId(), p.sku(), p.name().values(), p.status().name(), p.categoryId(), v.variantSku(), i.tracked(),
				i.tracked() ? i.onHand() : null, i.tracked() ? i.reserved() : null, i.tracked() ? i.available() : null, i.lowStockThreshold(), i.state().name(), v.version(), v.updatedAt());
	}

	private static Movement movement(StockMovement m) {
		return new Movement(m.id(), m.inventoryItemId(), m.type().name(), m.delta(), m.balanceAfter(), m.reason().name(), m.reservationId(), m.note(), m.at(), m.recordedBy());
	}
}
