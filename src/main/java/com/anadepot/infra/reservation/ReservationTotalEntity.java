package com.anadepot.infra.reservation;

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
@Table(name = "reservation_totals")
class ReservationTotalEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id")
	ReservationEntity reservation;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal totalAmount;
	BigDecimal paidAmount;

	protected ReservationTotalEntity() {
	}
}
