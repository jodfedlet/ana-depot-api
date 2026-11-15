package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.NoteVisibility;
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
@Table(name = "reservation_notes")
class ReservationNoteEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id")
	ReservationEntity reservation;
	@Enumerated(EnumType.STRING)
	NoteVisibility visibility;
	String body;
	@Enumerated(EnumType.STRING)
	ActorKind authorKind;
	UUID authorUserId;
	Instant createdAt;

	protected ReservationNoteEntity() {
	}
}
