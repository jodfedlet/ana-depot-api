package com.anadepot.infra.catalog;

import com.anadepot.core.shared.money.SupportedCurrency;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** ONE row per (product, currency): the product's prices in that currency. */
@Entity
@Table(name = "product_prices")
class ProductPriceEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	ProductEntity product;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal retailPrice;
	BigDecimal salePrice;
	int minOrderQuantity;
	boolean wholesaleEnabled;
	boolean active;
	Instant createdAt;
	Instant updatedAt;

	protected ProductPriceEntity() {
	}
}
