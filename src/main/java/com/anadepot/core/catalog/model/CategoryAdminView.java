package com.anadepot.core.catalog.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A category as staff see it: the definition plus bookkeeping and how much of the catalog depends on it.
 * {@code attributeValueCounts}: products holding a value for each attribute; {@code optionUseCounts}: values and variant axes choosing each option.
 */
public record CategoryAdminView(Category category, long version, Instant createdAt, Instant updatedAt, long productCount, long activeProductCount,
		Map<UUID, Long> attributeValueCounts, Map<UUID, Long> optionUseCounts) {
}
