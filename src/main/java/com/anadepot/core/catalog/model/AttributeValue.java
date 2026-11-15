package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.math.BigDecimal;
import java.util.UUID;

/** A product's value for one category attribute: exactly one of option (CHOICE), text (TEXT) or number (NUMBER). */
public record AttributeValue(UUID attributeId, UUID optionId, String text, BigDecimal number) {

	public AttributeValue {
		int present = (optionId != null ? 1 : 0) + (text != null ? 1 : 0) + (number != null ? 1 : 0);
		if (present != 1) {
			throw new DomainRuleViolation("attribute_value_invalid", "An attribute value holds exactly one of option, text or number");
		}
	}

	public static AttributeValue choice(UUID attributeId, UUID optionId) {
		return new AttributeValue(attributeId, optionId, null, null);
	}

	public static AttributeValue text(UUID attributeId, String text) {
		return new AttributeValue(attributeId, null, text, null);
	}

	public static AttributeValue number(UUID attributeId, BigDecimal number) {
		return new AttributeValue(attributeId, null, null, number);
	}
}
