package com.anadepot.infra.outbox;

import java.util.Map;
import java.util.UUID;

/** What a handler receives. {@code eventId} is stable across redeliveries: deduplicate on it. */
public record OutboxMessage(UUID eventId, String type, String aggregateType, UUID aggregateId, long aggregateVersion,
		Map<String, Object> payload, String correlationId, int attempt) {
}
