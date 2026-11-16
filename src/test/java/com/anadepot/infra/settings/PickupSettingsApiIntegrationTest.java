package com.anadepot.infra.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.core.reservation.service.SubmitReservationCommand;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Pickup settings over HTTP + the reservation gate that depends on them. Settings are global, so each test starts from "never configured". */
class PickupSettingsApiIntegrationTest extends ApiTestSupport {

	@Autowired StoreSettingJpaRepository settingsJpa;
	@Autowired AuditEventJpaRepository audit;

	@BeforeEach
	void neverConfigured() {
		settingsJpa.deleteAll();
	}

	private Map<String, Object> body(boolean enabled, Long version) {
		var m = new java.util.HashMap<>(base(enabled));
		if (version != null) {
			m.put("version", version);
		}
		return m;
	}

	private Map<String, Object> base(boolean enabled) {
		var schedule = List.of(Map.of("day", 1, "open", true, "from", "08:00", "to", "16:00"), Map.of("day", 2, "open", false, "from", "09:00", "to", "17:00"));
		return Map.of("enabled", enabled, "unavailableMessage", Map.of("fr", "Fermé pour travaux (test)", "ht", ""),
				"location", Map.of("storeName", "Test Store", "addressLine", "0 Test Street", "city", "Test City", "region", "Test Region", "country", "ht", "phone", "+509 0000 0000",
						"email", "test@example.test", "schedule", schedule, "additionalLocationInfo", Map.of("fr", "info", "ht", ""), "closureInfo", Map.of()),
				"instructions", Map.of("general", Map.of("fr", "Venez avec votre référence", "ht", "Vini ak referans ou")),
				"holdPeriodDays", 3);
	}

	@Test
	void beforeTheOwnerConfiguresAnythingPickupIsNotConfiguredAndNothingIsInvented() throws Exception {
		get("/api/v1/store/pickup", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("not_configured"))
				.andExpect(jsonPath("$.location").doesNotExist())
				.andExpect(jsonPath("$.instructions").doesNotExist());
		var admin = newStaffToken();
		get("/api/v1/admin/settings/pickup", admin).andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false))
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.holdPeriodDays").doesNotExist())   // no default hold period
				.andExpect(jsonPath("$.location.storeName").value(""));
	}

	@Test
	void staffSavesAndThePublicEndpointOnlyExposesAvailableSettings() throws Exception {
		var admin = newStaffToken();
		var saved = put("/api/v1/admin/settings/pickup", body(true, null), admin).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.location.country").value("HT"))                // normalised to upper case
				.andExpect(jsonPath("$.holdPeriodDays").value(3))
				.andExpect(jsonPath("$.updatedBy").isNotEmpty());
		get("/api/v1/store/pickup", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("available"))
				.andExpect(jsonPath("$.location.addressLine").value("0 Test Street"))
				.andExpect(jsonPath("$.location.schedule.length()").value(2))
				.andExpect(jsonPath("$.instructions.general.ht").value("Vini ak referans ou"))
				.andExpect(jsonPath("$.instructions.policy.fr").value(""))          // both language keys always present
				.andExpect(jsonPath("$.holdPeriodDays").doesNotExist());              // internal setting, not public
		// disabled: only the owner's public message, never the location
		put("/api/v1/admin/settings/pickup", body(false, 0L), admin).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		get("/api/v1/store/pickup", null).andExpect(jsonPath("$.status").value("disabled"))
				.andExpect(jsonPath("$.unavailableMessage.fr").value("Fermé pour travaux (test)"))
				.andExpect(jsonPath("$.location").doesNotExist());
		assertThat(saved).isNotNull();
	}

	@Test
	void concurrentEditsAreDetectedWithTheVersionAndStaleWritersGet409() throws Exception {
		var admin = newStaffToken();
		put("/api/v1/admin/settings/pickup", body(true, null), admin).andExpect(status().isOk());
		put("/api/v1/admin/settings/pickup", body(true, 0L), admin).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		put("/api/v1/admin/settings/pickup", body(true, 0L), admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_update"));
		put("/api/v1/admin/settings/pickup", body(true, null), admin).andExpect(status().isConflict());     // "never saved" is also stale now
	}

	@Test
	void invalidSettingsAreRejectedWithFieldCodes_andEnabledNeedsTheEssentials() throws Exception {
		var admin = newStaffToken();
		var bad = Map.of("enabled", true, "location", Map.of("storeName", "", "phone", "12", "email", "nope", "country", "h1",
				"schedule", List.of(Map.of("day", 1, "open", true, "from", "17:00", "to", "09:00"))), "holdPeriodDays", 0);
		put("/api/v1/admin/settings/pickup", bad, admin).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("validation_failed"))
				.andExpect(jsonPath("$.errors[?(@.field=='location.storeName')].code").value("required"))
				.andExpect(jsonPath("$.errors[?(@.field=='location.country')].code").value("country_invalid"))
				.andExpect(jsonPath("$.errors[?(@.field=='location.phone')].code").value("phone_invalid"))
				.andExpect(jsonPath("$.errors[?(@.field=='location.email')].code").value("email_invalid"))
				.andExpect(jsonPath("$.errors[?(@.field=='location.schedule[0]')].code").value("schedule_hours"))
				.andExpect(jsonPath("$.errors[?(@.field=='holdPeriodDays')].code").value("hold_period_invalid"));
		// a disabled draft may be incomplete
		put("/api/v1/admin/settings/pickup", Map.of("enabled", false, "location", Map.of("storeName", "Only a name")), admin).andExpect(status().isOk());
	}

	@Test
	void onlyStaffMayReadOrChangeTheSettingsAndMalformedBodiesAreBadRequests() throws Exception {
		get("/api/v1/admin/settings/pickup", null).andExpect(status().isUnauthorized());
		put("/api/v1/admin/settings/pickup", body(true, null), null).andExpect(status().isUnauthorized());
		var customer = token(UUID.randomUUID());
		get("/api/v1/admin/settings/pickup", customer).andExpect(status().isForbidden());
		put("/api/v1/admin/settings/pickup", body(true, null), customer).andExpect(status().isForbidden());
		assertThat(settingsJpa.count()).isZero();                                        // nothing was written
		var admin = newStaffToken();
		send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/settings/pickup").contentType("application/json").content("{not json"), admin)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
		put("/api/v1/admin/settings/pickup", Map.of("enabled", true, "location", Map.of("schedule", List.of(Map.of("day", 1, "open", true, "from", "0123456789")))), admin)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed")).andExpect(jsonPath("$.errors[0].code").value("size"));
	}

	@Test
	void changesAreAuditedAndEmitAnOutboxEventInTheSameTransaction() throws Exception {
		var admin = newStaffToken();
		put("/api/v1/admin/settings/pickup", body(true, null), admin).andExpect(status().isOk());
		put("/api/v1/admin/settings/pickup", body(false, 0L), admin).andExpect(status().isOk());
		var trail = audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("store_settings", "pickup");
		assertThat(trail).extracting(e -> e.getAction()).containsOnly("PICKUP_SETTINGS_CHANGED");
		var last = trail.getLast();
		assertThat(last.getReason()).contains("enabled");
		assertThat(com.anadepot.infra.persistence.Json.readMap(last.getPreviousValues())).containsEntry("enabled", true);
		assertThat(com.anadepot.infra.persistence.Json.readMap(last.getNewValues())).containsEntry("enabled", false);
		assertThat(last.getActorKind()).isEqualTo("STAFF");
	}

	@Test
	void reservationsAreRefusedUntilPickupIsEnabledAndConfigured_thenTheCurrentSettingsAreFrozenOnThem() throws Exception {
		var product = stockedMattress(10);
		SubmitReservationCommand command = new SubmitReservationCommand(List.of(line(product, 1, SupportedCurrency.HTG)), contact("0000 4242"), "fr", null, Actor.guest(), key());
		assertThatThrownBy(() -> reservations.submit(command)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("pickup_not_configured");

		var admin = newStaffToken();
		put("/api/v1/admin/settings/pickup", body(false, null), admin).andExpect(status().isOk());
		assertThatThrownBy(() -> reservations.submit(command)).extracting("code").isEqualTo("pickup_disabled");

		put("/api/v1/admin/settings/pickup", body(true, 0L), admin).andExpect(status().isOk());
		var r = reservations.submit(command);
		var snapshot = com.anadepot.infra.persistence.Json.readMap(reservations.get(r.id()).pickupSnapshotJson());   // jsonb normalises whitespace: compare parsed values
		assertThat(snapshot).containsEntry("origin", "default").containsKey("capturedAt");
		assertThat(snapshot.get("location").toString()).contains("0 Test Street");
	}

	@Test
	void changingTheSettingsLaterNeverRewritesAnExistingReservationSnapshot() throws Exception {
		var product = stockedMattress(10);
		var admin = newStaffToken();
		put("/api/v1/admin/settings/pickup", body(true, null), admin).andExpect(status().isOk());
		var r = reservations.submit(new SubmitReservationCommand(List.of(line(product, 1, SupportedCurrency.USD)), contact("0000 4343"), "fr", null, Actor.guest(), key()));
		var changed = body(true, 0L);
		@SuppressWarnings("unchecked") var location = new java.util.HashMap<>((Map<String, Object>) changed.get("location"));
		location.put("addressLine", "99 Moved Street");
		var moved = new java.util.HashMap<>(changed);
		moved.put("location", location);
		put("/api/v1/admin/settings/pickup", moved, admin).andExpect(status().isOk());
		assertThat(reservations.get(r.id()).pickupSnapshotJson()).contains("0 Test Street").doesNotContain("99 Moved Street");
	}
}
