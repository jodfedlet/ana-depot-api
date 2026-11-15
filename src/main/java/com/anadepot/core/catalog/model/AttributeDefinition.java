package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.LocalizedText;
import java.util.List;
import java.util.UUID;

/**
 * A category-defined product attribute. Categories decide which attributes exist, which are required, which filter
 * the storefront and which can be variant axes — nothing about shoes, mattresses or poles is hardcoded.
 */
public record AttributeDefinition(UUID id, String key, LocalizedText label, AttributeType type, String unit,
		boolean required, boolean filterable, boolean variantAxis, int displayOrder, boolean active,
		List<AttributeOption> options) {

	public AttributeDefinition {
		if (key == null || key.isBlank()) {
			throw new DomainRuleViolation("attribute_key_required", "Attribute key is required");
		}
		if (label.isEmpty()) {
			throw new DomainRuleViolation("attribute_label_required", "Attribute " + key + " needs a label");
		}
		options = List.copyOf(options);
		if (type != AttributeType.CHOICE) {
			if (filterable || variantAxis) {
				throw new DomainRuleViolation("attribute_choice_only", "Only choice attributes can be filterable or variant axes: " + key);
			}
			if (!options.isEmpty()) {
				throw new DomainRuleViolation("attribute_options_not_allowed", "Only choice attributes have options: " + key);
			}
		} else if (options.isEmpty()) {
			throw new DomainRuleViolation("attribute_options_required", "Choice attribute " + key + " needs at least one option");
		}
	}

	public AttributeOption option(UUID optionId) {
		return options.stream().filter(o -> o.id().equals(optionId)).findFirst().orElse(null);
	}
}
