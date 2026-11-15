package com.anadepot.infra.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only consumer today: a structured log line per event. There is no notification channel yet (SMS / e-mail /
 * WhatsApp is an open business decision), so nothing external is called. Contains no personal data by design.
 */
@Component
class LoggingOutboxEventHandler implements OutboxEventHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventHandler.class);

	@Override
	public String name() {
		return "event-log";
	}

	@Override
	public boolean supports(String eventType) {
		return true;
	}

	@Override
	public void handle(OutboxMessage m) {
		log.info("domain event type={} aggregate={}/{} version={} eventId={} attempt={}", m.type(), m.aggregateType(), m.aggregateId(), m.aggregateVersion(), m.eventId(), m.attempt());
	}
}
