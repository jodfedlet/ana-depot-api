package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.AttemptStatus;
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
@Table(name = "payment_attempts")
class PaymentAttemptEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<UUID> {

	@Id
	UUID id;
	UUID reservationId;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal amount;
	String provider;
	@Enumerated(EnumType.STRING)
	AttemptStatus status;
	String providerSessionId;
	String providerPaymentIntentId;
	String checkoutUrl;
	Instant expiresAt;
	UUID paymentId;
	boolean requiresAttention;
	String attentionReason;
	String failureCode;
	UUID createdByUserId;
	Instant createdAt;
	Instant updatedAt;

	protected PaymentAttemptEntity() {
	}

	@Override
	public UUID getId() {
		return id;
	}
}
