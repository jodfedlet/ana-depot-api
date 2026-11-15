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

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
