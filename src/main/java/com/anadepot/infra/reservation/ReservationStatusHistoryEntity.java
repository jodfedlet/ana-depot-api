package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.ReservationStatus;
import com.anadepot.core.shared.ActorKind;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservation_status_history")
class ReservationStatusHistoryEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id")
	ReservationEntity reservation;
	int seq;
	@Enumerated(EnumType.STRING)
	ReservationStatus fromStatus;
	@Enumerated(EnumType.STRING)
	ReservationStatus toStatus;
	String reasonCode;
	String reasonNote;
	Instant changedAt;
	@Enumerated(EnumType.STRING)
	ActorKind actorKind;
	UUID actorUserId;

	protected ReservationStatusHistoryEntity() {
	}
}
