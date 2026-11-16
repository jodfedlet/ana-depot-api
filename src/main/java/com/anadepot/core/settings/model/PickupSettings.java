package com.anadepot.core.settings.model;

import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.LocalizedText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The store's pickup configuration (the one settings area that has a real model). {@code version} is null until the owner saves it
 * for the first time; {@code holdPeriodDays} is null = no hold/expiration policy (the business has not decided one: no default exists).
 * Rules mirror the storefront's form validation; the server is authoritative.
 */
public record PickupSettings(boolean enabled, LocalizedText unavailableMessage, PickupLocation location, PickupInstructions instructions,
		Integer holdPeriodDays, Long version, Instant updatedAt, UUID updatedBy) {

	public enum Availability {
		AVAILABLE, DISABLED, NOT_CONFIGURED
	}

	private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
	private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}");
	private static final Pattern PHONE = Pattern.compile("\\+?[\\d\\s().-]+");

	public PickupSettings {
		unavailableMessage = unavailableMessage == null ? LocalizedText.empty() : unavailableMessage;
		location = location == null ? PickupLocation.empty() : location;
		instructions = instructions == null ? PickupInstructions.empty() : instructions;
	}

	/** What exists before the owner configures anything: disabled, empty, no policy. */
	public static PickupSettings notConfigured() {
		return new PickupSettings(false, LocalizedText.empty(), PickupLocation.empty(), PickupInstructions.empty(), null, null, null, null);
	}

	/** Pickup can accept new reservations only when enabled AND the essentials are filled in. */
	public Availability availability() {
		if (version == null) {
			return Availability.NOT_CONFIGURED; // never saved: the owner has not configured anything yet
		}
		if (!enabled) {
			return Availability.DISABLED;
		}
		var l = location;
		if (l.storeName().isBlank() || l.addressLine().isBlank() || l.city().isBlank() || l.phone().isBlank()) {
			return Availability.NOT_CONFIGURED;
		}
		return Availability.AVAILABLE;
	}

	/** Reservations are refused unless pickup is available. */
	public void requireAvailable() {
		switch (availability()) {
			case DISABLED -> throw new DomainRuleViolation("pickup_disabled", "Pickup is currently unavailable");
			case NOT_CONFIGURED -> throw new DomainRuleViolation("pickup_not_configured", "Pickup details have not been configured yet");
			case AVAILABLE -> {
			}
		}
	}

	/** Throws {@link FieldValidationException} listing every problem (field paths use dots, e.g. {@code location.phone}). */
	public void validate() {
		var issues = new ArrayList<FieldIssue>();
		var l = location;
		if (enabled) {
			required(issues, "location.storeName", l.storeName());
			required(issues, "location.addressLine", l.addressLine());
			required(issues, "location.city", l.city());
			required(issues, "location.region", l.region());
			required(issues, "location.country", l.country());
			required(issues, "location.phone", l.phone());
			if (l.schedule().stream().noneMatch(DaySchedule::open)) {
				issues.add(new FieldIssue("location.schedule", "schedule_one_day"));
			}
		}
		if (!l.country().isBlank() && !l.country().matches("[A-Z]{2}")) {
			issues.add(new FieldIssue("location.country", "country_invalid"));
		}
		if (!l.phone().isBlank() && !validPhone(l.phone())) {
			issues.add(new FieldIssue("location.phone", "phone_invalid"));
		}
		if (l.email() != null && !EMAIL.matcher(l.email()).matches()) {
			issues.add(new FieldIssue("location.email", "email_invalid"));
		}
		var days = new HashSet<Integer>();
		for (int i = 0; i < l.schedule().size(); i++) {
			var d = l.schedule().get(i);
			var path = "location.schedule[" + i + "]";
			if (d.day() < 1 || d.day() > 7 || !days.add(d.day())) {
				issues.add(new FieldIssue(path + ".day", "day_invalid"));
			}
			if (d.open()) {
				if (d.from() == null || d.to() == null || !TIME.matcher(d.from()).matches() || !TIME.matcher(d.to()).matches()) {
					issues.add(new FieldIssue(path, "time_invalid"));
				} else if (d.from().compareTo(d.to()) >= 0) {
					issues.add(new FieldIssue(path, "schedule_hours"));
				}
			}
		}
		if (holdPeriodDays != null && holdPeriodDays < 1) {
			issues.add(new FieldIssue("holdPeriodDays", "hold_period_invalid"));
		}
		text(issues, "unavailableMessage", unavailableMessage);
		text(issues, "location.additionalLocationInfo", l.additionalLocationInfo());
		text(issues, "location.closureInfo", l.closureInfo());
		text(issues, "instructions.general", instructions.general());
		text(issues, "instructions.contact", instructions.contact());
		text(issues, "instructions.accepted", instructions.accepted());
		text(issues, "instructions.ready", instructions.ready());
		text(issues, "instructions.policy", instructions.policy());
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
	}

	private static void text(List<FieldIssue> issues, String field, LocalizedText t) {
		if (t.values().values().stream().anyMatch(v -> v.length() > 2000)) {
			issues.add(new FieldIssue(field, "too_long"));
		}
	}

	private static void required(List<FieldIssue> issues, String field, String value) {
		if (value == null || value.isBlank()) {
			issues.add(new FieldIssue(field, "required"));
		}
	}

	private static boolean validPhone(String v) {
		if (!PHONE.matcher(v.strip()).matches()) {
			return false;
		}
		int digits = v.replaceAll("\\D", "").length();
		return digits >= 6 && digits <= 15;
	}

	public PickupSettings withAudit(Long newVersion, Instant at, UUID by) {
		return new PickupSettings(enabled, unavailableMessage, location, instructions, holdPeriodDays, newVersion, at, by);
	}

	/** Names of the groups of settings that differ (for the audit trail and the change event). */
	public List<String> changedGroups(PickupSettings other) {
		var out = new ArrayList<String>();
		if (enabled != other.enabled) {
			out.add("enabled");
		}
		if (!unavailableMessage.equals(other.unavailableMessage)) {
			out.add("unavailableMessage");
		}
		var a = location;
		var b = other.location;
		if (!a.storeName().equals(b.storeName()) || !a.addressLine().equals(b.addressLine()) || !a.city().equals(b.city()) || !a.region().equals(b.region())
				|| !a.country().equals(b.country()) || !java.util.Objects.equals(a.postalCode(), b.postalCode()) || !a.additionalLocationInfo().equals(b.additionalLocationInfo())) {
			out.add("address");
		}
		if (!a.phone().equals(b.phone()) || !java.util.Objects.equals(a.email(), b.email())) {
			out.add("contact");
		}
		if (!a.schedule().equals(b.schedule()) || !a.closureInfo().equals(b.closureInfo())) {
			out.add("schedule");
		}
		if (!instructions.equals(other.instructions)) {
			out.add("instructions");
		}
		if (!java.util.Objects.equals(holdPeriodDays, other.holdPeriodDays)) {
			out.add("holdPeriod");
		}
		return out;
	}
}
