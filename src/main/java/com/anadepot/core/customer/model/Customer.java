package com.anadepot.core.customer.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who reserved goods. Two kinds of record, never merged automatically: a GUEST row (no {@code authUserId}) created
 * from a guest reservation's contact details, unique per phone number; and a REGISTERED row owned by one identity-provider
 * user ({@code authUserId}, unique). A signed-in customer's reservations attach to their own registered row only.
 * {@code phone} holds digits only so the same number typed differently is the same customer.
 */
public record Customer(UUID id, String firstName, String lastName, String dialCode, String phone, String email,
		UUID authUserId, Instant createdAt) {

	/** A registered customer (owns an account). Guests are rows with no {@code authUserId}; the two are never merged automatically. */
	public boolean registered() {
		return authUserId != null;
	}

	public static String normalizePhone(String raw) {
		return raw == null ? "" : raw.replaceAll("\\D", "");
	}
}
