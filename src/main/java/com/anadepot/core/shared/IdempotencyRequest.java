package com.anadepot.core.shared;

/**
 * Identifies one retriable command: {@code actorScope} (who is asking), {@code operation} (what), {@code key}
 * (client-chosen) and a {@code fingerprint} of the request payload. Same scope+operation+key with the same fingerprint
 * is a retry; with a different fingerprint it is a misuse of the key.
 */
public record IdempotencyRequest(String actorScope, String operation, String key, String fingerprint) {

	public IdempotencyRequest {
		if (key == null || key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9_\\-:.]+")) {
			throw new DomainRuleViolation("idempotency_key_invalid", "Idempotency key must be 8-128 characters of letters, digits and _-:.");
		}
	}
}
