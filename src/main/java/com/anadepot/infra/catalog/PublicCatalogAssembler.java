package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.core.inventory.service.InventoryService;
import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds public product responses for a page with ONE category query and ONE stock query (no per-product lookups). */
@Component
class PublicCatalogAssembler {

	private final CatalogService catalog;
	private final InventoryService inventory;

	PublicCatalogAssembler(CatalogService catalog, InventoryService inventory) {
		this.catalog = catalog;
		this.inventory = inventory;
	}

	@Transactional(readOnly = true)
	ProductDtos.PageResponse<ProductDtos.ProductResponse> page(PageResult<Product> result) {
		return new ProductDtos.PageResponse<>(responses(result.items()), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@Transactional(readOnly = true)
	ProductDtos.ProductResponse one(Product product) {
		return responses(List.of(product)).getFirst();
	}

	private List<ProductDtos.ProductResponse> responses(List<Product> products) {
		var categories = catalog.categoriesById(products.stream().map(Product::categoryId).collect(java.util.stream.Collectors.toSet()));
		Map<UUID, List<com.anadepot.core.inventory.model.InventoryItem>> stock = inventory.positionsOf(products.stream().map(Product::id).toList());
		return products.stream().map(p -> {
			Category c = categories.get(p.categoryId());
			return ProductDtos.ProductResponse.of(p, c == null ? null : c.slug(), stock.getOrDefault(p.id(), List.of()));
		}).toList();
	}
}
