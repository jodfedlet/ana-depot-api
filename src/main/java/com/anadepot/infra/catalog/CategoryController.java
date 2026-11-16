package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.service.CatalogService;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public categories: ACTIVE only, with the number of active products and (on the detail) the active attribute definitions and options. */
@RestController
@RequestMapping("/api/v1/categories")
class CategoryController {

	private final CatalogService catalog;

	CategoryController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	@Transactional(readOnly = true)
	List<ProductDtos.CategoryResponse> list() {
		var counts = catalog.publicProductCounts();
		return catalog.listPublicCategories().stream().map(c -> ProductDtos.CategoryResponse.of(c, counts.getOrDefault(c.id(), 0L), false)).toList();
	}

	@GetMapping("/{slug}")
	@Transactional(readOnly = true)
	ProductDtos.CategoryResponse get(@PathVariable String slug) {
		var category = catalog.getPublicCategory(slug);
		return ProductDtos.CategoryResponse.of(category, catalog.publicProductCounts().getOrDefault(category.id(), 0L), true);
	}
}
