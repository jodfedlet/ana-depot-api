package com.anadepot.infra.settings;

import com.anadepot.core.settings.model.DaySchedule;
import com.anadepot.core.settings.model.PickupInstructions;
import com.anadepot.core.settings.model.PickupLocation;
import com.anadepot.core.settings.model.PickupSettings;
import com.anadepot.core.shared.LocalizedText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PickupMapper {

	private PickupMapper() {
	}

	/** Both language keys always present (empty string when not written), like the storefront's LText. */
	static Map<String, String> text(LocalizedText t) {
		var m = new LinkedHashMap<String, String>();
		m.put("fr", "");
		m.put("ht", "");
		m.putAll(t.values());
		return m;
	}

	static LocalizedText text(Map<String, String> m) {
		return m == null ? LocalizedText.empty() : new LocalizedText(m);
	}

	static PickupDtos.LocationDto dto(PickupLocation l) {
		return new PickupDtos.LocationDto(l.storeName(), l.addressLine(), l.city(), l.region(), l.country(), l.postalCode(), l.phone(), l.email(), text(l.additionalLocationInfo()),
				l.schedule().stream().map(d -> new PickupDtos.ScheduleDto(d.day(), d.open(), d.from(), d.to())).toList(), text(l.closureInfo()));
	}

	static PickupDtos.InstructionsDto dto(PickupInstructions i) {
		return new PickupDtos.InstructionsDto(text(i.general()), text(i.contact()), text(i.accepted()), text(i.ready()), text(i.policy()));
	}

	/** The stored form: the effective hold period is computed when the settings are read, never stored. */
	static PickupDtos.PickupSettingsDto dto(PickupSettings s) {
		return dto(s, null);
	}

	static PickupDtos.PickupSettingsDto dto(PickupSettings s, Integer effectiveHoldDays) {
		return new PickupDtos.PickupSettingsDto(s.enabled(), text(s.unavailableMessage()), dto(s.location()), dto(s.instructions()), s.holdPeriodDays(), effectiveHoldDays, s.version(), s.updatedAt(),
				s.updatedBy() == null ? null : s.updatedBy().toString());
	}

	static PickupSettings domain(PickupDtos.PickupSettingsDto d) {
		var l = d.location();
		PickupLocation location = l == null ? PickupLocation.empty() : new PickupLocation(l.storeName(), l.addressLine(), l.city(), l.region(), l.country() == null ? null : l.country().strip().toUpperCase(),
				l.postalCode(), l.phone(), l.email(), text(l.additionalLocationInfo()),
				l.schedule() == null ? List.of() : l.schedule().stream().map(x -> new DaySchedule(x.day(), x.open(), x.from(), x.to())).toList(), text(l.closureInfo()));
		var i = d.instructions();
		var instructions = i == null ? PickupInstructions.empty() : new PickupInstructions(text(i.general()), text(i.contact()), text(i.accepted()), text(i.ready()), text(i.policy()));
		return new PickupSettings(d.enabled(), text(d.unavailableMessage()), location, instructions, d.holdPeriodDays(), d.version(), null, null);
	}
}
