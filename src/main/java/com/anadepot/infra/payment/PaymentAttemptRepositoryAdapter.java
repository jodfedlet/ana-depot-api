package com.anadepot.infra.payment;

import com.anadepot.core.payment.model.AttemptStatus;
import com.anadepot.core.payment.model.PaymentAttempt;
import com.anadepot.core.payment.repository.PaymentAttemptRepository;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class PaymentAttemptRepositoryAdapter implements PaymentAttemptRepository {

	private final PaymentAttemptJpaRepository attempts;
	private final ProviderEventJpaRepository events;
	@PersistenceContext
	private EntityManager em;

	PaymentAttemptRepositoryAdapter(PaymentAttemptJpaRepository attempts, ProviderEventJpaRepository events) {
		this.attempts = attempts;
		this.events = events;
	}

	@Override
	public PaymentAttempt insert(PaymentAttempt a) {
		var e = new PaymentAttemptEntity();
		e.id = a.id();
		e.reservationId = a.reservationId();
		e.currency = a.amount().currency();
		e.amount = a.amount().amount();
		e.provider = "STRIPE";
		e.createdByUserId = a.createdByUserId();
		e.createdAt = a.createdAt();
		copy(a, e);
		attempts.saveAndFlush(e);
		return a;
	}

	@Override
	public PaymentAttempt update(PaymentAttempt a) {
		var e = attempts.findById(a.id()).orElseThrow();
		copy(a, e);
		attempts.saveAndFlush(e);
		return a;
	}

	private static void copy(PaymentAttempt a, PaymentAttemptEntity e) {
		e.status = a.status();
		e.providerSessionId = a.sessionId();
		e.providerPaymentIntentId = a.paymentIntentId();
		e.checkoutUrl = a.checkoutUrl();
		e.expiresAt = a.expiresAt();
		e.paymentId = a.paymentId();
		e.requiresAttention = a.requiresAttention();
		e.attentionReason = a.attentionReason();
		e.failureCode = a.failureCode();
		e.updatedAt = a.updatedAt();
	}

	@Override
	public Optional<PaymentAttempt> find(UUID id) {
		return attempts.findById(id).map(PaymentAttemptRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<PaymentAttempt> findBySession(String sessionId) {
		return attempts.findByProviderSessionId(sessionId).map(PaymentAttemptRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<PaymentAttempt> findByPaymentIntent(String intentId) {
		return attempts.findByProviderPaymentIntentId(intentId).map(PaymentAttemptRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<PaymentAttempt> findOpen(UUID reservationId, String currency) {
		return em.createQuery("select a from PaymentAttemptEntity a where a.reservationId = :r and a.currency = :c and a.status in :open", PaymentAttemptEntity.class)
				.setParameter("r", reservationId).setParameter("c", SupportedCurrency.parse(currency)).setParameter("open", List.of(AttemptStatus.PENDING, AttemptStatus.PROCESSING))
				.getResultList().stream().findFirst().map(PaymentAttemptRepositoryAdapter::toDomain);
	}

	@Override
	public List<PaymentAttempt> forReservation(UUID reservationId, int limit) {
		return em.createQuery("select a from PaymentAttemptEntity a where a.reservationId = :r order by a.createdAt desc, a.id", PaymentAttemptEntity.class)
				.setParameter("r", reservationId).setMaxResults(limit).getResultList().stream().map(PaymentAttemptRepositoryAdapter::toDomain).toList();
	}

	@Override
	public PageResult<PaymentAttempt> page(UUID reservationId, AttemptStatus status, Boolean requiresAttention, PageRequest page) {
		var from = new StringBuilder(" from PaymentAttemptEntity a where 1 = 1");
		var params = new LinkedHashMap<String, Object>();
		if (reservationId != null) {
			from.append(" and a.reservationId = :r");
			params.put("r", reservationId);
		}
		if (status != null) {
			from.append(" and a.status = :s");
			params.put("s", status);
		}
		if (requiresAttention != null) {
			from.append(" and a.requiresAttention = :att");
			params.put("att", requiresAttention);
		}
		var query = em.createQuery("select a" + from + " order by a.createdAt desc, a.id", PaymentAttemptEntity.class);
		var count = em.createQuery("select count(a)" + from, Long.class);
		params.forEach((k, v) -> {
			query.setParameter(k, v);
			count.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		return new PageResult<>(query.getResultList().stream().map(PaymentAttemptRepositoryAdapter::toDomain).toList(), page.page(), page.size(), count.getSingleResult());
	}

	@Override
	public boolean recordEvent(String provider, String eventId, String eventType, UUID attemptId, String outcome, Instant at) {
		var e = new ProviderEventEntity();
		e.id = UUID.randomUUID();
		e.provider = provider;
		e.eventId = eventId;
		e.eventType = eventType;
		e.attemptId = attemptId;
		e.outcome = outcome;
		e.receivedAt = at;
		events.saveAndFlush(e);
		return true;
	}

	@Override
	public boolean eventSeen(String provider, String eventId) {
		return events.existsByProviderAndEventId(provider, eventId);
	}

	private static PaymentAttempt toDomain(PaymentAttemptEntity e) {
		return new PaymentAttempt(e.id, e.reservationId, new Money(e.amount, e.currency), e.status, e.providerSessionId, e.providerPaymentIntentId, e.checkoutUrl, e.expiresAt, e.paymentId,
				e.requiresAttention, e.attentionReason, e.failureCode, e.createdByUserId, e.createdAt, e.updatedAt);
	}
}
