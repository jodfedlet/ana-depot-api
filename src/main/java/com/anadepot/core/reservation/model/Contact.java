package com.anadepot.core.reservation.model;

import com.anadepot.core.shared.DomainRuleViolation;

/** Guest contact details, copied onto the reservation (no account required). */
public record Contact(String firstName, String lastName, String dialCode, String phone, String email) {

	public Contact {
		if (isBlank(firstName) || isBlank(lastName)) {
			throw new DomainRuleViolation("contact_invalid", "First and last name are required");
		}
		if (isBlank(dialCode) || phone == null || phone.replaceAll("\\D", "").length() < 6) {
			throw new DomainRuleViolation("contact_invalid", "A valid phone number is required");
		}
		if (!isBlank(email) && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw new DomainRuleViolation("contact_invalid", "E-mail looks invalid");
		}
		email = isBlank(email) ? null : email.strip();
	}

	/**
	 * Is {@code typed} this contact's phone number? Compared on digits only: the national number alone ("3700 0000"), or the
	 * dial code plus the national number ("+509 3700 0000", "00509 3700 0000"). Deliberately stricter than the storefront's demo
	 * check: no arbitrary-suffix matching (typing the last six digits is not enough). Fewer than 6 digits never matches.
	 */
	public boolean phoneMatches(String typed) {
		var input = digitsOf(typed);
		if (input.startsWith("00")) {
			input = input.substring(2);
		}
		if (input.length() < 6) {
			return false;
		}
		var national = digitsOf(phone);
		var withDialCode = digitsOf(dialCode) + national;
		// both comparisons always run and are constant-time per candidate
		boolean a = sameDigits(input, national);
		boolean b = sameDigits(input, withDialCode);
		return a | b;
	}

	private static String digitsOf(String s) {
		return s == null ? "" : s.replaceAll("\\D", "");
	}

	private static boolean sameDigits(String a, String b) {
		return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
