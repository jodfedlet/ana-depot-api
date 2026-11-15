package com.anadepot.infra.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

	List<PaymentEntity> findByReservationIdOrderByRecordedAtAscIdAsc(UUID reservationId);
}
