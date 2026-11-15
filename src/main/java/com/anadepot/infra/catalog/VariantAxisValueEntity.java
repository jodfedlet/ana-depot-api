package com.anadepot.infra.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "product_variant_axis_values")
class VariantAxisValueEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "variant_id")
	ProductVariantEntity variant;
	UUID attributeId;
	UUID optionId;

	protected VariantAxisValueEntity() {
	}
}
