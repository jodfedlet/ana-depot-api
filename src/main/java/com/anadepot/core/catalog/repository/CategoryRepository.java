package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.Category;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

	Category save(Category category);

	Optional<Category> findById(UUID id);
}
