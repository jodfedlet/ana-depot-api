package com.anadepot.infra.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

	Optional<PaymentAttemptEntity> findByProviderSessionId(String sessionId);

	Optional<PaymentAttemptEntity> findByProviderPaymentIntentId(String intentId);
}
