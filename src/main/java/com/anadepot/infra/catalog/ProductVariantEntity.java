package com.anadepot.infra.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "product_variants")
class ProductVariantEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	ProductEntity product;
	String sku;
	boolean active;
	int position;
	@OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
	List<VariantAxisValueEntity> axisValues = new ArrayList<>();
	@OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
	List<VariantPriceEntity> prices = new ArrayList<>();

	protected ProductVariantEntity() {
	}
}
