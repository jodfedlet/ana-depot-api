package com.anadepot.infra.reservation;

import com.anadepot.core.reservation.service.HoldPolicy;
import com.anadepot.core.settings.service.StoreSettingsService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** The hold period is the store's own setting (pickup settings, days) or, when it set none, the approved default of three days. */
@Component
class StoreHoldPolicy implements HoldPolicy {

	private final StoreSettingsService settings;
	private final ReservationPolicyProperties properties;

	StoreHoldPolicy(StoreSettingsService settings, ReservationPolicyProperties properties) {
		this.settings = settings;
		this.properties = properties;
	}

	@Override
	public Duration holdPeriod() {
		var days = settings.getPickup().holdPeriodDays();
		return Duration.ofDays(days != null && days > 0 ? days : properties.defaultHoldDays());
	}

	@Override
	public Duration reminderLead() {
		return properties.reminderLead();
	}
}
