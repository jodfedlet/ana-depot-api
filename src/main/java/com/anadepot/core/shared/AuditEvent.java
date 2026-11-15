package com.anadepot.core.shared;

import java.util.Map;

/**
 * Something a person or the system did to a sensitive resource: who ({@code actor}), what ({@code action}), to what
 * ({@code targetType}/{@code targetId}), with the {@code previous} and {@code current} values where that makes sense
 * and the {@code reason} when one is required. Timestamp and correlation id are added when it is recorded.
 * Never put secrets, tokens or payment-instrument data in the maps.
 */
public record AuditEvent(String action, String targetType, String targetId, Actor actor,
		Map<String, Object> previous, Map<String, Object> current, String reason) {

	public static AuditEvent of(String action, String targetType, Object targetId, Actor actor, Map<String, Object> previous, Map<String, Object> current, String reason) {
		return new AuditEvent(action, targetType, String.valueOf(targetId), actor, previous == null ? Map.of() : previous, current == null ? Map.of() : current, reason);
	}
}
