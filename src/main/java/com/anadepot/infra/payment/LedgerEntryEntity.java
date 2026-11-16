package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.LedgerEntryType;
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
@Table(name = "ledger_entries")
class LedgerEntryEntity extends com.anadepot.infra.persistence.InsertOnlyEntity<UUID> {

	@Id
	UUID id;
	UUID reservationId;
	UUID paymentId;
	@Enumerated(EnumType.STRING)
	LedgerEntryType entryType;
	@Enumerated(EnumType.STRING)
	SupportedCurrency currency;
	BigDecimal amount;
	UUID reversesEntryId;
	String reason;
	Instant createdAt;
	UUID createdBy;
	String correlationId;

	protected LedgerEntryEntity() {
	}

	@Override
	public UUID getId() {
		return id;
	}
}
