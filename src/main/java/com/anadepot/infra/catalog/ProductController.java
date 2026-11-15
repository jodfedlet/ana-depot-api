package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only catalog. Only ACTIVE products are visible; an inactive or draft product is a 404 (not a 403, so
 * its existence is not revealed). One product, several currency prices: see {@code prices}.
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController {

	private final CatalogService catalog;

	ProductController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	ProductDtos.PageResponse<ProductDtos.ProductResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var result = catalog.listProducts(new PageRequest(page, size), ProductStatus.ACTIVE);
		return new ProductDtos.PageResponse<>(result.items().stream().map(ProductDtos.ProductResponse::of).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@GetMapping("/{id}")
	ProductDtos.ProductResponse get(@PathVariable UUID id) {
		var product = catalog.getProduct(id);
		if (product.status() != ProductStatus.ACTIVE) {
			throw new NotFoundException("Product", id);
		}
		return ProductDtos.ProductResponse.of(product);
	}
}
