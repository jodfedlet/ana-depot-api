package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.ReservationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "reservations")
class ReservationEntity {

	@Id
	UUID id;
	String reference;
	@Enumerated(EnumType.STRING)
	ReservationStatus status;
	String locale;
	UUID customerId;
	String contactFirstName;
	String contactLastName;
	String contactDialCode;
	String contactPhone;
	String contactEmail;
	@Column(columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	String pickupSnapshot;
	Instant readyAt;
	Instant expiresAt;
	Instant createdAt;
	Instant updatedAt;
	@Version
	Long version;
	@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
	List<ReservationTotalEntity> totals = new ArrayList<>();
	@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	List<ReservationLineEntity> lines = new ArrayList<>();
	@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("seq ASC")
	List<ReservationStatusHistoryEntity> history = new ArrayList<>();
	@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("createdAt ASC")
	List<ReservationNoteEntity> notes = new ArrayList<>();

	protected ReservationEntity() {
	}
}
