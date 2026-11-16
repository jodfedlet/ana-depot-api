package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.Category;
import com.anadepot.core.catalog.repository.CategoryRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
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

	@Override
	public java.util.List<Category> findActive() {
		return categories.findByActiveTrueOrderByDisplayOrderAscSlugAsc().stream().map(CatalogMapper::toDomain).toList();
	}

	@Override
	public Optional<Category> findActiveBySlug(String slug) {
		return categories.findBySlugAndActiveTrue(slug).map(CatalogMapper::toDomain);
	}

	@Override
	public java.util.Map<UUID, Category> findAllById(java.util.Collection<UUID> ids) {
		var result = new java.util.HashMap<UUID, Category>();
		categories.findAllById(ids).forEach(e -> result.put(e.id, CatalogMapper.toDomain(e)));
		return result;
	}

	@Override
	public java.util.Map<UUID, Long> activeProductCounts() {
		var result = new java.util.HashMap<UUID, Long>();
		for (var row : categories.activeProductCounts()) {
			result.put((UUID) row[0], (Long) row[1]);
		}
		return result;
	}
}
