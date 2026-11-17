package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request bodies of the guest/account reservation endpoints and their validation. Validation is explicit (not bean validation) so
 * every problem is reported at once, as {@code {field, code}} pairs with stable codes, in a 422 problem. Unknown JSON properties
 * (for instance a client-supplied {@code unitPrice}) are ignored: prices are NEVER taken from the client.
 */
public final class ReservationRequests {

	private ReservationRequests() {
	}

	static final int MAX_LINES = 50;
	static final int MAX_QUANTITY = 10_000;
	private static final Set<String> LOCALES = Set.of("fr", "ht");
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
	private static final Pattern PHONE_CHARS = Pattern.compile("[0-9 ()./+-]{1,32}");

	public record LineRequest(UUID productId, UUID variantId, Integer quantity, String currency) {
	}

	public record ContactRequest(String firstName, String lastName, String dialCode, String phone, String email) {
		/** Personal data: never printed (a stray log statement or DEBUG logging must not leak it). */
		@Override
		public String toString() {
			return "ContactRequest[redacted]";
		}
	}

	public record CreateReservationRequest(List<LineRequest> lines, ContactRequest contact, String locale, Boolean acknowledgedTerms) {
	}

	public record LookupRequest(String reference, String phone) {
		/** Personal data: never printed (a stray log statement or DEBUG logging must not leak it). */
		@Override
		public String toString() {
			return "LookupRequest[redacted]";
		}
	}

	public record ProfileRequest(String firstName, String lastName, String dialCode, String phone, String email) {
		/** Personal data: never printed (a stray log statement or DEBUG logging must not leak it). */
		@Override
		public String toString() {
			return "ProfileRequest[redacted]";
		}
	}

	/** Validates everything, collecting all issues; returns the domain command only when there are none. */
	static SubmitReservationCommand toCommand(CreateReservationRequest body, com.anadepot.core.shared.Actor actor, String idempotencyKey) {
		var issues = new ArrayList<FieldIssue>();
		var lines = new ArrayList<SubmitReservationCommand.Line>();
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		if (body.lines() == null || body.lines().isEmpty()) {
			issues.add(new FieldIssue("lines", "cart_empty"));
		} else if (body.lines().size() > MAX_LINES) {
			issues.add(new FieldIssue("lines", "too_many_lines"));
		} else {
			for (int i = 0; i < body.lines().size(); i++) {
				var l = body.lines().get(i);
				var at = "lines[" + i + "].";
				if (l == null) {
					issues.add(new FieldIssue("lines[" + i + "]", "required"));
					continue;
				}
				if (l.productId() == null) {
					issues.add(new FieldIssue(at + "productId", "required"));
				}
				if (l.quantity() == null || l.quantity() < 1 || l.quantity() > MAX_QUANTITY) {
					issues.add(new FieldIssue(at + "quantity", "quantity_invalid"));
				}
				SupportedCurrency currency = null;
				if (l.currency() == null || l.currency().isBlank()) {
					issues.add(new FieldIssue(at + "currency", "required"));
				} else {
					try {
						currency = SupportedCurrency.parse(l.currency());
					} catch (DomainRuleViolation e) {
						issues.add(new FieldIssue(at + "currency", "currency_invalid"));
					}
				}
				if (l.productId() != null && l.quantity() != null && currency != null && l.quantity() >= 1 && l.quantity() <= MAX_QUANTITY) {
					lines.add(new SubmitReservationCommand.Line(l.productId(), l.variantId(), l.quantity(), currency));
				}
			}
		}
		var contact = contact("contact.", body.contact(), issues);
		String locale = body.locale() == null ? null : body.locale().strip().toLowerCase(java.util.Locale.ROOT);
		if (locale == null || locale.isEmpty()) {
			issues.add(new FieldIssue("locale", "required"));
		} else if (!LOCALES.contains(locale)) {
			issues.add(new FieldIssue("locale", "invalid"));
		}
		if (!Boolean.TRUE.equals(body.acknowledgedTerms())) {
			issues.add(new FieldIssue("acknowledgedTerms", "terms_not_acknowledged"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return new SubmitReservationCommand(List.copyOf(lines), contact, locale, null, actor, idempotencyKey);
	}

	public static Contact toContact(ProfileRequest body) {
		var issues = new ArrayList<FieldIssue>();
		if (body == null) {
			throw new FieldValidationException(List.of(new FieldIssue("body", "required")));
		}
		var contact = contact("", new ContactRequest(body.firstName(), body.lastName(), body.dialCode(), body.phone(), body.email()), issues);
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return contact;
	}

	/** Field-by-field contact validation; {@code prefix} is "contact." or "". Returns null when any issue was recorded. */
	private static Contact contact(String prefix, ContactRequest c, List<FieldIssue> issues) {
		if (c == null) {
			issues.add(new FieldIssue(prefix.isEmpty() ? "body" : prefix.substring(0, prefix.length() - 1), "required"));
			return null;
		}
		int before = issues.size();
		name(prefix + "firstName", c.firstName(), issues);
		name(prefix + "lastName", c.lastName(), issues);
		String dial = null;
		if (c.dialCode() == null || c.dialCode().isBlank()) {
			issues.add(new FieldIssue(prefix + "dialCode", "required"));
		} else {
			var digits = c.dialCode().replaceAll("\\D", "");
			if (digits.isEmpty() || digits.length() > 4 || !c.dialCode().strip().matches("\\+?\\s*[0-9 ]+")) {
				issues.add(new FieldIssue(prefix + "dialCode", "invalid"));
			} else {
				dial = "+" + digits;   // one canonical spelling, so "509" and "+509" are the same guest
			}
		}
		if (c.phone() == null || c.phone().isBlank()) {
			issues.add(new FieldIssue(prefix + "phone", "required"));
		} else if (!PHONE_CHARS.matcher(c.phone().strip()).matches() || c.phone().replaceAll("\\D", "").length() < 6 || c.phone().replaceAll("\\D", "").length() > 15) {
			issues.add(new FieldIssue(prefix + "phone", "invalid"));
		}
		if (c.email() != null && !c.email().isBlank() && (c.email().strip().length() > 254 || !EMAIL.matcher(c.email().strip()).matches())) {
			issues.add(new FieldIssue(prefix + "email", "invalid"));
		}
		return issues.size() > before ? null : new Contact(c.firstName().strip(), c.lastName().strip(), dial, c.phone().strip(), c.email());
	}

	private static void name(String field, String value, List<FieldIssue> issues) {
		if (value == null || value.isBlank()) {
			issues.add(new FieldIssue(field, "required"));
		} else if (value.strip().length() > 100 || value.chars().anyMatch(Character::isISOControl)) {
			issues.add(new FieldIssue(field, "invalid"));
		}
	}
}
