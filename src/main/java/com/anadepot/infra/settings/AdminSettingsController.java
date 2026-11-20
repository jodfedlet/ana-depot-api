package com.anadepot.infra.settings;

import com.anadepot.core.settings.service.StoreSettingsService;
import com.anadepot.infra.security.Actors;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff-only (SecurityConfig: /api/v1/admin/** requires STAFF). Optimistic concurrency through {@code version}. */
@RestController
@RequestMapping("/api/v1/admin/settings/pickup")
class AdminSettingsController {

	private final StoreSettingsService settings;
	private final com.anadepot.core.reservation.service.HoldPolicy holdPolicy;

	AdminSettingsController(StoreSettingsService settings, com.anadepot.core.reservation.service.HoldPolicy holdPolicy) {
		this.settings = settings;
		this.holdPolicy = holdPolicy;
	}

	@GetMapping
	PickupDtos.PickupSettingsDto get() {
		return PickupMapper.dto(settings.getPickup(), (int) holdPolicy.holdPeriod().toDays());
	}

	@PutMapping
	PickupDtos.PickupSettingsDto put(@Valid @RequestBody PickupDtos.PickupSettingsDto body, Authentication authentication) {
		var saved = settings.savePickup(PickupMapper.domain(body), body.version(), Actors.staff(authentication));
		return PickupMapper.dto(saved, (int) holdPolicy.holdPeriod().toDays());
	}
}
