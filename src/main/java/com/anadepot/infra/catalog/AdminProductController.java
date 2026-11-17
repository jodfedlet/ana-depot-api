package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.AdminProductQuery;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.SearchQuery;
import com.anadepot.core.catalog.service.ProductAdminService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.security.Actors;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Staff product management ({@code /api/v1/admin/products}). Parsing and shaping only; every rule lives in {@code ProductAdminService}. Images: {@code AdminProductImageController}. */
@RestController
@RequestMapping("/api/v1/admin/products")
class AdminProductController {

	private final ProductAdminService products;

	AdminProductController(ProductAdminService products) {
		this.products = products;
	}

	@GetMapping
	ProductDtos.PageResponse<AdminProductDtos.Summary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String brand,
			@RequestParam(required = false) String currency, @RequestParam(required = false) String stock, @RequestParam(required = false) String sort) {
		var query = new AdminProductQuery(SearchQuery.of(q), enumOf(ProductStatus.class, status, "status"), categoryId, brand, enumOf(SupportedCurrency.class, currency, "currency"),
				enumOf(AdminProductQuery.StockFilter.class, stock, "stock"), enumOf(AdminProductQuery.Sort.class, sort, "sort"));
		var result = products.list(query, new PageRequest(page, size));
		return new ProductDtos.PageResponse<>(result.items().stream().map(AdminProductDtos::summary).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@GetMapping("/{id}")
	AdminProductDtos.Detail get(@PathVariable UUID id) {
		return AdminProductDtos.detail(products.get(id));
	}

	@PostMapping
	ResponseEntity<AdminProductDtos.Detail> create(@RequestBody AdminProductDtos.ProductRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			Authentication authentication) {
		return ResponseEntity.status(HttpStatus.CREATED).body(AdminProductDtos.detail(products.create(draft(body), Actors.staff(authentication), idempotencyKey)));
	}

	@PutMapping("/{id}")
	AdminProductDtos.Detail update(@PathVariable UUID id, @RequestBody AdminProductDtos.ProductRequest body, Authentication authentication) {
		if (body == null || body.version() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("version", "required")));
		}
		return AdminProductDtos.detail(products.update(id, body.version(), draft(body), Actors.staff(authentication)));
	}

	@PatchMapping("/{id}/status")
	AdminProductDtos.Detail setStatus(@PathVariable UUID id, @RequestBody AdminProductDtos.StatusRequest body, Authentication authentication) {
		if (body == null || body.status() == null || body.status().isBlank()) {
			throw new FieldValidationException(List.of(new FieldIssue("status", "required")));
		}
		if (body.reason() != null && body.reason().length() > 500) {
			throw new FieldValidationException(List.of(new FieldIssue("reason", "too_long")));
		}
		var status = enumOf(ProductStatus.class, body.status(), "status");
		return AdminProductDtos.detail(products.setStatus(id, status, body.reason(), Actors.staff(authentication)));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
		products.delete(id, Actors.staff(authentication));
		return ResponseEntity.noContent().build();
	}

	private static com.anadepot.core.catalog.model.ProductDraft draft(AdminProductDtos.ProductRequest body) {
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		return body.toDraft();
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new FieldValidationException(List.of(new FieldIssue(field, "invalid")));
		}
	}
}
