package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.LedgerEntryType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryEntity, UUID> {

	List<LedgerEntryEntity> findByReservationIdOrderByCreatedAtAscIdAsc(UUID reservationId);

	Optional<LedgerEntryEntity> findByPaymentIdAndEntryType(UUID paymentId, LedgerEntryType type);

	boolean existsByReversesEntryId(UUID reversesEntryId);
}
