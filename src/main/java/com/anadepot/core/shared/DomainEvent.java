package com.anadepot.core.shared;

import java.util.Map;
import java.util.UUID;

/**
 * A business fact that happened and may need to trigger asynchronous work (notifications, projections, ...).
 * Recorded through the transactional outbox: it is stored in the SAME transaction as the change and delivered later,
 * at least once. Consumers must therefore be idempotent (the event id is stable: use it to deduplicate).
 */
public record DomainEvent(String type, String aggregateType, UUID aggregateId, long aggregateVersion, Map<String, Object> payload) {
}
