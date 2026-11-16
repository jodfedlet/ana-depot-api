package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.SearchDocument;
import java.util.UUID;

/** Port: stores the searchable text of a product. Implemented with a PostgreSQL tsvector; callers invoke it inside the transaction of the change. */
public interface ProductSearchIndex {

	void index(UUID productId, SearchDocument document);
}
