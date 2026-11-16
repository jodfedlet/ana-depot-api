package com.anadepot.core.settings.repository;

import com.anadepot.core.settings.model.PickupSettings;
import java.util.Optional;

public interface PickupSettingsRepository {

	Optional<PickupSettings> find();

	/**
	 * Stores the settings if {@code expectedVersion} matches the stored version (null = nothing stored yet), else throws
	 * {@code DomainRuleViolation("concurrent_update")}. Returns the stored settings with the new version.
	 */
	PickupSettings save(PickupSettings settings, Long expectedVersion);
}
