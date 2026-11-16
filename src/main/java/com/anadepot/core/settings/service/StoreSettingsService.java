package com.anadepot.core.settings.service;

import com.anadepot.core.settings.model.PickupSettings;
import com.anadepot.core.shared.Actor;

public interface StoreSettingsService {

	/** Never null: before the first save this is {@link PickupSettings#notConfigured()} (version null). */
	PickupSettings getPickup();

	/**
	 * Validates and stores the pickup settings with optimistic concurrency ({@code expectedVersion}: the version the editor loaded,
	 * null when nothing had been saved). Writes an audit entry (changed groups, previous/new values) and an outbox event in the same transaction.
	 */
	PickupSettings savePickup(PickupSettings candidate, Long expectedVersion, Actor actor);
}
