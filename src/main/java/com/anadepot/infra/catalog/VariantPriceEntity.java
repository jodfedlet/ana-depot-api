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
import java.util.UUID;

/** A variant's own retail price in one currency (only allowed where the product itself has a price in that currency). */
@Entity
@Table(name = "variant_prices")
class VariantPriceEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "variant_id")
	ProductVariantEntity variant;
	UUID productId;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal retailPrice;

	protected VariantPriceEntity() {
	}
}
