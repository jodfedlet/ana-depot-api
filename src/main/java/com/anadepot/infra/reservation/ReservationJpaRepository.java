package com.anadepot.infra.reservation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

	/** Row lock: serialises concurrent transitions and payments on the same reservation. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ReservationEntity r where r.id = :id")
	Optional<ReservationEntity> lockById(UUID id);

	Optional<ReservationEntity> findByReference(String reference);

	boolean existsByReference(String reference);

	org.springframework.data.domain.Page<ReservationEntity> findByCustomerIdOrderByCreatedAtDescIdAsc(UUID customerId, org.springframework.data.domain.Pageable pageable);

	@Query("select coalesce(sum(l.quantity), 0) from ReservationLineEntity l where l.reservation.status in ('PENDING_REVIEW', 'CONFIRMED', 'READY_FOR_PICKUP') and l.productId = :productId and l.variantId is null")
	long openHeldForProduct(UUID productId);

	@Query("select coalesce(sum(l.quantity), 0) from ReservationLineEntity l where l.reservation.status in ('PENDING_REVIEW', 'CONFIRMED', 'READY_FOR_PICKUP') and l.variantId = :variantId")
	long openHeldForVariant(UUID variantId);
}
