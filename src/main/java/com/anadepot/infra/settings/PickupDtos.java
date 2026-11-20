package com.anadepot.infra.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the pickup configuration (also the stored JSON and the reservation snapshot). Localized texts are
 * {@code {"fr": "...", "ht": "..."}}; responses always carry both keys (empty string when not written yet) to match the storefront's LText.
 */
final class PickupDtos {

	private PickupDtos() {
	}

	record ScheduleDto(int day, boolean open, @Size(max = 5) String from, @Size(max = 5) String to) {
	}

	record LocationDto(@Size(max = 200) String storeName, @Size(max = 300) String addressLine, @Size(max = 120) String city, @Size(max = 120) String region,
			@Size(max = 2) String country, @Size(max = 20) String postalCode, @Size(max = 40) String phone, @Size(max = 254) String email,
			Map<String, String> additionalLocationInfo, @Valid @Size(max = 7) List<ScheduleDto> schedule, Map<String, String> closureInfo) {
	}

	record InstructionsDto(Map<String, String> general, Map<String, String> contact, Map<String, String> accepted, Map<String, String> ready, Map<String, String> policy) {
	}

	/**
	 * Admin view/edit. {@code version}: null = never saved; PUT must echo the version that was loaded. {@code holdPeriodDays}: the store's own number (null = none set);
	 * {@code effectiveHoldPeriodDays}: what is actually applied to a newly ready reservation (the store's number, else the approved default, D6). Read-only: ignored on PUT.
	 */
	record PickupSettingsDto(boolean enabled, Map<String, String> unavailableMessage, @Valid LocationDto location, @Valid InstructionsDto instructions,
			Integer holdPeriodDays, Integer effectiveHoldPeriodDays, Long version, Instant updatedAt, String updatedBy) {
	}

	/** Public view: location and instructions ONLY when pickup is available; otherwise just the status (and the owner's public message when disabled). */
	record PublicPickupResponse(String status, Map<String, String> unavailableMessage, LocationDto location, InstructionsDto instructions) {
	}

	/** What is frozen on a reservation. */
	record SnapshotDto(LocationDto location, InstructionsDto instructions, Instant capturedAt, String origin) {
	}
}
