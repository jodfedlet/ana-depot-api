package com.anadepot.infra.payment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderEventJpaRepository extends JpaRepository<ProviderEventEntity, UUID> {

	boolean existsByProviderAndEventId(String provider, String eventId);
}
