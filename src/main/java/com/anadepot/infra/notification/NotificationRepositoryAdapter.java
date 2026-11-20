package com.anadepot.infra.notification;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class NotificationRepositoryAdapter implements NotificationRepository {

	private final NotificationJpaRepository jpa;

	NotificationRepositoryAdapter(NotificationJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public boolean insertIfAbsent(Notification n) {
		// The reservation (or payment) row is locked by the operation that records the message, so two requests for one business fact are serialized: this check cannot race.
		if (jpa.existsByDedupeKey(n.dedupeKey())) {
			return false;
		}
		jpa.saveAndFlush(toEntity(n, new NotificationEntity()));
		return true;
	}

	@Override
	public long countRecentForRecipient(String recipientHash, Instant since) {
		return jpa.countRecent(recipientHash, since);
	}

	@Override
	public List<Notification> claimDue(Instant now, int limit, Instant leaseUntil) {
		var rows = jpa.claimDue(now, org.springframework.data.domain.PageRequest.of(0, limit));
		for (var e : rows) {
			e.status = NotificationStatus.PROCESSING.name();
			e.leaseUntil = leaseUntil;
			e.attempts++;
			e.updatedAt = now;
		}
		jpa.flush();
		return rows.stream().map(NotificationRepositoryAdapter::toDomain).toList();
	}

	@Override
	public Optional<Notification> find(UUID id) {
		return jpa.findById(id).map(NotificationRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Notification> findForUpdate(UUID id) {
		return jpa.lockById(id).map(NotificationRepositoryAdapter::toDomain);
	}

	@Override
	public Notification save(Notification n) {
		var e = jpa.findById(n.id()).orElseGet(NotificationEntity::new);
		return toDomain(jpa.saveAndFlush(toEntity(n, e)));
	}

	@Override
	public PageResult<Notification> list(NotificationQuery q, PageRequest page) {
		var result = jpa.search(q.status() == null ? null : q.status().name(), q.type() == null ? null : q.type().name(), q.reservationId(),
				org.springframework.data.domain.PageRequest.of(page.page(), page.size()));
		return new PageResult<>(result.getContent().stream().map(NotificationRepositoryAdapter::toDomain).toList(), page.page(), page.size(), result.getTotalElements());
	}

	@Override
	public Map<NotificationStatus, Long> counts() {
		var out = new EnumMap<NotificationStatus, Long>(NotificationStatus.class);
		for (var s : NotificationStatus.values()) {
			out.put(s, 0L);
		}
		jpa.countByStatus().forEach(row -> out.put(NotificationStatus.valueOf((String) row[0]), (Long) row[1]));
		return out;
	}

	@Override
	public long attentionCount() {
		var important = java.util.Arrays.stream(NotificationType.values()).filter(NotificationType::important).map(Enum::name).toList();
		return jpa.attention(important);
	}

	@Override
	public Instant oldestWaiting() {
		return jpa.oldestWaiting();
	}

	Map<UUID, String> referencesOf(java.util.Collection<UUID> reservationIds) {
		var out = new java.util.HashMap<UUID, String>();
		if (!reservationIds.isEmpty()) {
			jpa.referencesOf(reservationIds).forEach(row -> out.put((UUID) row[0], (String) row[1]));
		}
		return out;
	}

	private static NotificationEntity toEntity(Notification n, NotificationEntity e) {
		e.id = n.id();
		e.eventId = n.eventId();
		e.dedupeKey = n.dedupeKey();
		e.type = n.type().name();
		e.aggregateType = "reservation";
		e.aggregateId = n.reservationId();
		e.reservationId = n.reservationId();
		e.paymentId = n.paymentId();
		e.attemptId = n.attemptId();
		e.locale = n.locale();
		e.recipientKind = "RESERVATION_CONTACT";
		e.recipientHint = n.recipientHint();
		e.recipientHash = n.recipientHash();
		e.status = n.status().name();
		e.attempts = n.attempts();
		e.nextAttemptAt = n.nextAttemptAt();
		e.lastErrorCode = n.lastErrorCode();
		e.lastErrorAt = n.lastErrorAt();
		e.sentAt = n.sentAt();
		e.createdAt = n.createdAt();
		e.updatedAt = n.updatedAt();
		e.revivedAt = n.revivedAt();
		if (n.status() != NotificationStatus.PROCESSING) {
			e.leaseUntil = null;
		}
		return e;
	}

	private static Notification toDomain(NotificationEntity e) {
		return new Notification(e.id, e.eventId, e.dedupeKey, NotificationType.valueOf(e.type), e.reservationId, e.paymentId, e.attemptId, e.locale, e.recipientHint, e.recipientHash,
				NotificationStatus.valueOf(e.status), e.attempts, e.nextAttemptAt, e.lastErrorCode, e.lastErrorAt, e.sentAt, e.createdAt, e.updatedAt, e.revivedAt);
	}
}
