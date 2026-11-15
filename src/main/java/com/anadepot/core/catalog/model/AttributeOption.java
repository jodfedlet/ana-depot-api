package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.LocalizedText;
import java.util.UUID;

/** A selectable value of a CHOICE attribute. {@code code} is stable and language-independent. */
public record AttributeOption(UUID id, String code, LocalizedText label, int displayOrder, boolean active) {

	public AttributeOption {
		if (code == null || code.isBlank()) {
			throw new DomainRuleViolation("option_code_required", "Option code is required");
		}
		if (label.isEmpty()) {
			throw new DomainRuleViolation("option_label_required", "Option " + code + " needs a label");
		}
	}
}
