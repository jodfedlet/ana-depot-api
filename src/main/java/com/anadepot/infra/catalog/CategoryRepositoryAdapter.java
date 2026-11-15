package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.repository.CategoryRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class CategoryRepositoryAdapter implements CategoryRepository {

	private final CategoryJpaRepository categories;
	private final Clock clock;

	CategoryRepositoryAdapter(CategoryJpaRepository categories, Clock clock) {
		this.categories = categories;
		this.clock = clock;
	}

	@Override
	public Category save(Category category) {
		var now = clock.instant();
		var entity = categories.findById(category.id()).orElseGet(() -> {
			var n = new CategoryEntity();
			n.id = category.id();
			n.createdAt = now;
			return n;
		});
		CatalogMapper.apply(category, entity, now);
		return CatalogMapper.toDomain(categories.saveAndFlush(entity));
	}

	@Override
	public Optional<Category> findById(UUID id) {
		return categories.findById(id).map(CatalogMapper::toDomain);
	}
}
