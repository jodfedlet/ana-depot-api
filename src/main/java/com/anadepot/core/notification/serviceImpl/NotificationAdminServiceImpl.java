package com.anadepot.core.notification.serviceImpl;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.notification.service.NotificationAdminService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public class NotificationAdminServiceImpl implements NotificationAdminService {

	private final NotificationRepository notifications;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;
	private final Clock clock;

	public NotificationAdminServiceImpl(NotificationRepository notifications, AuditRecorder audit, TransactionBoundary tx, Clock clock) {
		this.notifications = notifications;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public PageResult<Notification> list(NotificationQuery query, PageRequest page) {
		return tx.inTransaction(() -> notifications.list(query, page));
	}

	@Override
	public Map<NotificationStatus, Long> summary() {
		return tx.inTransaction(notifications::counts);
	}

	@Override
	public Notification retry(UUID id, Actor actor) {
		return tx.inTransaction(() -> {
			var n = notifications.findForUpdate(id).orElseThrow(() -> new NotFoundException("Notification", id));
			if (n.status() != NotificationStatus.DEAD) {
				throw new DomainRuleViolation("notification_not_retryable", "Only a message that gave up can be retried.");
			}
			var now = clock.instant();
			var saved = notifications.save(n.with(NotificationStatus.PENDING, 0, now, null, null, null, now));
			audit.record(AuditEvent.of("NOTIFICATION_RETRY_REQUESTED", "notification", id, actor, Map.of("status", n.status().name()), Map.of("status", NotificationStatus.PENDING.name(), "type", n.type().name()), null));
			return saved;
		});
	}
}
