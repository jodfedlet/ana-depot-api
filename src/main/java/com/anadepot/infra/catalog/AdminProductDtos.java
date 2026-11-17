package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductPrice;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff-facing product shapes (every status, every price configuration). Separate from the public shapes and from the entities. */
final class AdminProductDtos {

	private AdminProductDtos() {
	}

	/** One line of a staff product list. */
	record Summary(UUID id, String sku, String slug, UUID categoryId, Map<String, String> name, String brand, String sellingUnit, String status, List<String> currencies,
			int variantCount, int imageCount, Instant createdAt, Instant updatedAt) {

		static Summary of(Product p) {
			return new Summary(p.id(), p.sku(), p.slug(), p.categoryId(), p.name().values(), p.brand(), p.unit().name(), p.status().name(),
					p.pricing().all().stream().filter(ProductPrice::active).map(x -> x.currency().name()).sorted().toList(), p.variants().size(), p.images().size(), p.createdAt(), p.updatedAt());
		}
	}
}
