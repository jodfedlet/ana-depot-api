package com.anadepot.core.customer.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who reserved goods. Created from a guest reservation's contact details (no account needed);
 * {@code authUserId} is filled later if the person signs in with the identity provider.
 * {@code phone} holds digits only so the same number typed differently is the same customer.
 */
public record Customer(UUID id, String firstName, String lastName, String dialCode, String phone, String email,
		UUID authUserId, Instant createdAt) {

	public static String normalizePhone(String raw) {
		return raw == null ? "" : raw.replaceAll("\\D", "");
	}
}
