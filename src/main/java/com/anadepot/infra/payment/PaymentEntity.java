package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.PaymentMethod;
import com.anadepot.core.shared.money.SupportedCurrency;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
class PaymentEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<UUID> {

	@Id
	UUID id;
	UUID reservationId;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal amount;
	@Enumerated(EnumType.STRING)
	PaymentMethod paymentMethod;
	Instant receivedAt;
	Instant recordedAt;
	UUID recordedBy;
	String reference;
	String note;

	protected PaymentEntity() {
	}

	@Override
	public UUID getId() {
		return id;
	}
}
