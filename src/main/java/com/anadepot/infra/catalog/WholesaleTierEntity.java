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

/** A wholesale tier of ONE product in ONE currency (the database ties it to that product_prices row). */
@Entity
@Table(name = "wholesale_price_tiers")
class WholesaleTierEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	ProductEntity product;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	int minQuantity;
	BigDecimal unitPrice;

	protected WholesaleTierEntity() {
	}
}
