package com.anadepot.infra.outbox;

import com.anadepot.core.shared.CorrelationContext;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.infra.persistence.Json;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores the event in the CALLER's transaction ({@code MANDATORY}: refusing to run outside one is the whole point). */
@Component
public class OutboxEventRecorder implements DomainEventRecorder {

	private final OutboxJpaRepository repository;
	private final CorrelationContext correlation;
	private final Clock clock;

	OutboxEventRecorder(OutboxJpaRepository repository, CorrelationContext correlation, Clock clock) {
		this.repository = repository;
		this.correlation = correlation;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(DomainEvent event) {
		var e = new OutboxEventEntity();
		e.id = UUID.randomUUID();
		e.eventType = event.type();
		e.aggregateType = event.aggregateType();
		e.aggregateId = event.aggregateId();
		e.aggregateVersion = event.aggregateVersion();
		e.payload = Json.write(event.payload());
		e.correlationId = correlation.correlationId();
		e.createdAt = clock.instant();
		e.status = "PENDING";
		e.nextAttemptAt = e.createdAt;
		repository.save(e);
	}
}
