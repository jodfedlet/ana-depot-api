package com.anadepot.infra.settings;

import com.anadepot.core.settings.service.StoreSettingsService;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public pickup information. Location and instructions are returned ONLY when pickup is enabled and configured; otherwise just a status
 * ({@code disabled} with the owner's public message, or {@code not_configured}). Nothing is ever invented.
 */
@RestController
@RequestMapping("/api/v1/store/pickup")
class StorePickupController {

	private final StoreSettingsService settings;

	StorePickupController(StoreSettingsService settings) {
		this.settings = settings;
	}

	@GetMapping
	PickupDtos.PublicPickupResponse get() {
		var s = settings.getPickup();
		var status = s.availability();
		var statusText = status.name().toLowerCase(Locale.ROOT);
		return switch (status) {
			case AVAILABLE -> new PickupDtos.PublicPickupResponse(statusText, null, PickupMapper.dto(s.location()), PickupMapper.dto(s.instructions()));
			case DISABLED -> new PickupDtos.PublicPickupResponse(statusText, PickupMapper.text(s.unavailableMessage()), null, null);
			case NOT_CONFIGURED -> new PickupDtos.PublicPickupResponse(statusText, null, null, null);
		};
	}
}
