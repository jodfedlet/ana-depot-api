package com.anadepot.infra.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "product_attribute_values")
class ProductAttributeValueEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	ProductEntity product;
	UUID attributeId;
	UUID optionId;
	String textValue;
	BigDecimal numberValue;

	protected ProductAttributeValueEntity() {
	}
}
