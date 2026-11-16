package com.anadepot.infra.settings;

import com.anadepot.core.reservation.repository.PickupSnapshotProvider;
import com.anadepot.core.settings.service.StoreSettingsService;
import com.anadepot.infra.persistence.Json;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Freezes the CURRENT pickup settings as JSON for a new reservation; refuses when pickup is disabled or not configured. */
@Component
class JsonPickupSnapshotProvider implements PickupSnapshotProvider {

	private final StoreSettingsService settings;
	private final Clock clock;

	JsonPickupSnapshotProvider(StoreSettingsService settings, Clock clock) {
		this.settings = settings;
		this.clock = clock;
	}

	@Override
	public String currentSnapshotJson() {
		var current = settings.getPickup();
		current.requireAvailable();
		return Json.write(new PickupDtos.SnapshotDto(PickupMapper.dto(current.location()), PickupMapper.dto(current.instructions()), clock.instant(), "default"));
	}
}
