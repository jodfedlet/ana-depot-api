package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.AttributeDefinition;
import com.anadepot.core.catalog.model.AttributeOption;
import com.anadepot.core.catalog.model.CategoryAdminView;
import com.anadepot.core.catalog.model.CategoryDraft;
import com.anadepot.core.catalog.service.CategoryAdminService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.infra.security.Actors;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff category management ({@code /api/v1/admin/categories}). Parsing and shaping only; every rule lives in {@code CategoryAdminService}. */
@RestController
@RequestMapping("/api/v1/admin/categories")
class AdminCategoryController {

	record OptionRequest(UUID id, String code, Map<String, String> label, Integer displayOrder, Boolean active) {
	}

	record AttributeRequest(UUID id, String key, Map<String, String> label, String type, String unit, Boolean required, Boolean filterable, Boolean variantAxis,
			Integer displayOrder, Boolean active, List<OptionRequest> options) {
	}

	/** {@code version} is required when replacing (the version you read); ignored on creation. */
	record CategoryRequest(Long version, String slug, Map<String, String> name, Map<String, String> description, String imageUrl, Boolean active, Integer displayOrder,
			List<AttributeRequest> attributes) {

		CategoryDraft toDraft() {
			return new CategoryDraft(slug, name, description, imageUrl, active, displayOrder, attributes == null ? null : attributes.stream()
					.map(a -> a == null ? null : new CategoryDraft.AttributeDraft(a.id(), a.key(), a.label(), a.type(), a.unit(), a.required(), a.filterable(), a.variantAxis(), a.displayOrder(), a.active(),
							a.options() == null ? null : a.options().stream().map(o -> o == null ? null : new CategoryDraft.OptionDraft(o.id(), o.code(), o.label(), o.displayOrder(), o.active())).toList())).toList());
		}
	}

	record ActiveRequest(Boolean active, String reason) {
	}

	record OrderRequest(List<UUID> categoryIds) {
	}

	record MoveRequest(UUID toCategoryId, List<UUID> productIds) {
	}

	record OptionResponse(UUID id, String code, Map<String, String> label, int displayOrder, boolean active, Long useCount) {
	}

	record AttributeResponse(UUID id, String key, Map<String, String> label, String type, String unit, boolean required, boolean filterable, boolean variantAxis, int displayOrder,
			boolean active, Long valueCount, List<OptionResponse> options) {
	}

	record Summary(UUID id, String slug, Map<String, String> name, String imageUrl, boolean active, int displayOrder, int attributeCount, long productCount, long activeProductCount, long version) {
	}

	record Detail(UUID id, String slug, Map<String, String> name, Map<String, String> description, String imageUrl, boolean active, int displayOrder, long version, Instant createdAt,
			Instant updatedAt, long productCount, long activeProductCount, List<AttributeResponse> attributes) {
	}

	record MoveResponse(int moved) {
	}

	record OrderResponse(List<Summary> items) {
	}

	private final CategoryAdminService categories;

	AdminCategoryController(CategoryAdminService categories) {
		this.categories = categories;
	}

	@GetMapping
	ProductDtos.PageResponse<Summary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) Boolean active,
			@RequestParam(required = false) String q) {
		if (q != null && q.length() > 100) {
			throw new FieldValidationException(List.of(new FieldIssue("q", "too_long")));
		}
		var result = categories.list(new PageRequest(page, size), active, q);
		return new ProductDtos.PageResponse<>(result.items().stream().map(AdminCategoryController::summary).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@GetMapping("/{id}")
	Detail get(@PathVariable UUID id) {
		return detail(categories.get(id));
	}

	@PostMapping
	ResponseEntity<Detail> create(@RequestBody CategoryRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication) {
		return ResponseEntity.status(HttpStatus.CREATED).body(detail(categories.create(draft(body), Actors.staff(authentication), idempotencyKey)));
	}

	@PutMapping("/{id}")
	Detail update(@PathVariable UUID id, @RequestBody CategoryRequest body, Authentication authentication) {
		if (body == null || body.version() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("version", "required")));
		}
		return detail(categories.update(id, body.version(), draft(body), Actors.staff(authentication)));
	}

	@PatchMapping("/{id}/active")
	Detail setActive(@PathVariable UUID id, @RequestBody ActiveRequest body, Authentication authentication) {
		if (body == null || body.active() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("active", "required")));
		}
		if (body.reason() != null && body.reason().length() > 500) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "too_long")));
		}
		return detail(categories.setActive(id, body.active(), body.reason(), Actors.staff(authentication)));
	}

	@PutMapping("/order")
	OrderResponse reorder(@RequestBody OrderRequest body, Authentication authentication) {
		if (body == null || body.categoryIds() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("categoryIds", "required")));
		}
		return new OrderResponse(categories.reorder(body.categoryIds(), Actors.staff(authentication)).stream().map(AdminCategoryController::summary).toList());
	}

	@GetMapping("/{id}/products")
	ProductDtos.PageResponse<AdminProductDtos.Line> products(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var result = categories.products(id, new PageRequest(page, size));
		return new ProductDtos.PageResponse<>(result.items().stream().map(AdminProductDtos.Line::of).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@PostMapping("/{id}/products/move")
	MoveResponse move(@PathVariable UUID id, @RequestBody MoveRequest body, Authentication authentication) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.toCategoryId() == null) {
			issues.add(new FieldIssue("toCategoryId", "required"));
		}
		if (body == null || body.productIds() == null || body.productIds().isEmpty()) {
			issues.add(new FieldIssue("productIds", "required"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return new MoveResponse(categories.moveProducts(id, body.toCategoryId(), body.productIds(), Actors.staff(authentication)));
	}

	private static CategoryDraft draft(CategoryRequest body) {
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		return body.toDraft();
	}

	private static Summary summary(CategoryAdminView v) {
		var c = v.category();
		return new Summary(c.id(), c.slug(), c.name().values(), c.imageUrl(), c.active(), c.displayOrder(), c.attributes().size(), v.productCount(), v.activeProductCount(), v.version());
	}

	private static Detail detail(CategoryAdminView v) {
		var c = v.category();
		return new Detail(c.id(), c.slug(), c.name().values(), c.description().values(), c.imageUrl(), c.active(), c.displayOrder(), v.version(), v.createdAt(), v.updatedAt(),
				v.productCount(), v.activeProductCount(), c.attributes().stream().map(a -> attribute(a, v)).toList());
	}

	private static AttributeResponse attribute(AttributeDefinition a, CategoryAdminView v) {
		return new AttributeResponse(a.id(), a.key(), a.label().values(), a.type().name(), a.unit(), a.required(), a.filterable(), a.variantAxis(), a.displayOrder(), a.active(),
				v.attributeValueCounts().get(a.id()), a.options().stream().map(o -> option(o, v)).toList());
	}

	private static OptionResponse option(AttributeOption o, CategoryAdminView v) {
		return new OptionResponse(o.id(), o.code(), o.label().values(), o.displayOrder(), o.active(), v.optionUseCounts().get(o.id()));
	}
}
