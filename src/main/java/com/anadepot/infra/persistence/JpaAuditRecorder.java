package com.anadepot.infra.persistence;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.CorrelationContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Joins the caller's transaction: the audited change and its audit record commit or roll back together. */
@Component
public class JpaAuditRecorder implements AuditRecorder {

	private final AuditEventJpaRepository repository;
	private final CorrelationContext correlation;
	private final Clock clock;

	public JpaAuditRecorder(AuditEventJpaRepository repository, CorrelationContext correlation, Clock clock) {
		this.repository = repository;
		this.correlation = correlation;
		this.clock = clock;
	}

	@Override
	public void record(AuditEvent event) {
		var e = new AuditEventEntity();
		e.id = UUID.randomUUID();
		e.occurredAt = clock.instant();
		var actor = event.actor() == null ? Actor.system() : event.actor();
		e.actorKind = actor.kind().name();
		e.actorUserId = actor.userId();
		e.action = event.action();
		e.targetType = event.targetType();
		e.targetId = event.targetId();
		e.previousValues = Json.write(event.previous());
		e.newValues = Json.write(event.current());
		e.reason = event.reason();
		e.correlationId = correlation.correlationId();
		repository.save(e);
	}
}
