package com.anadepot.infra.reservation;

import com.anadepot.core.catalog.model.PricingKind;
import com.anadepot.core.catalog.model.SellingUnit;
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

@Entity
@Table(name = "reservation_lines")
class ReservationLineEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id")
	ReservationEntity reservation;
	int position;
	UUID productId;
	UUID variantId;
	String productName;
	String productSku;
	String variantLabel;
	@Enumerated(EnumType.STRING)
	SellingUnit sellingUnit;
	int quantity;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal unitPrice;
	@Enumerated(EnumType.STRING)
	PricingKind pricingKind;
	Integer tierMinQuantity;
	BigDecimal lineTotal;

	protected ReservationLineEntity() {
	}
}
