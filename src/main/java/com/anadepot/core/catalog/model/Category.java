package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.LocalizedText;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record Category(UUID id, String slug, LocalizedText name, LocalizedText description, String imageUrl,
		boolean active, int displayOrder, List<AttributeDefinition> attributes) {

	public Category {
		if (slug == null || slug.isBlank()) {
			throw new DomainRuleViolation("category_slug_required", "Category slug is required");
		}
		if (name.isEmpty()) {
			throw new DomainRuleViolation("category_name_required", "Category needs a name");
		}
		attributes = List.copyOf(attributes);
		var keys = new HashSet<String>();
		for (var a : attributes) {
			if (!keys.add(a.key())) {
				throw new DomainRuleViolation("attribute_key_duplicate", "Duplicate attribute key in category: " + a.key());
			}
		}
	}

	public AttributeDefinition attribute(UUID attributeId) {
		return attributes.stream().filter(a -> a.id().equals(attributeId)).findFirst().orElse(null);
	}
}
