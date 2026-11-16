package com.anadepot.core.settings.serviceImpl;

import com.anadepot.core.settings.model.PickupSettings;
import com.anadepot.core.settings.repository.PickupSettingsRepository;
import com.anadepot.core.settings.service.StoreSettingsService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class StoreSettingsServiceImpl implements StoreSettingsService {

	/** Stable aggregate id of the pickup settings in the outbox (there is exactly one). */
	public static final UUID PICKUP_AGGREGATE = UUID.nameUUIDFromBytes("store_settings:pickup".getBytes(java.nio.charset.StandardCharsets.UTF_8));

	private final PickupSettingsRepository settings;
	private final AuditRecorder audit;
	private final DomainEventRecorder events;
	private final TransactionBoundary tx;
	private final Clock clock;

	public StoreSettingsServiceImpl(PickupSettingsRepository settings, AuditRecorder audit, DomainEventRecorder events, TransactionBoundary tx, Clock clock) {
		this.settings = settings;
		this.audit = audit;
		this.events = events;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public PickupSettings getPickup() {
		return tx.inTransaction(() -> settings.find().orElseGet(PickupSettings::notConfigured));
	}

	@Override
	public PickupSettings savePickup(PickupSettings candidate, Long expectedVersion, Actor actor) {
		candidate.validate();
		return tx.inTransaction(() -> {
			var before = settings.find().orElseGet(PickupSettings::notConfigured);
			var who = actor == null ? Actor.system() : actor;
			var saved = settings.save(candidate.withAudit(null, clock.instant(), who.userId()), expectedVersion);
			var changed = before.changedGroups(saved);
			audit.record(AuditEvent.of("PICKUP_SETTINGS_CHANGED", "store_settings", "pickup", who, summary(before), summary(saved), String.join(",", changed)));
			events.record(new DomainEvent("settings.pickup_changed", "store_settings", PICKUP_AGGREGATE, saved.version() == null ? 0 : saved.version(),
					Map.of("changed", changed, "enabled", saved.enabled(), "availability", saved.availability().name())));
			return saved;
		});
	}

	/** Business configuration (not personal data) flattened for the audit trail. */
	private static Map<String, Object> summary(PickupSettings s) {
		var m = new LinkedHashMap<String, Object>();
		m.put("enabled", s.enabled());
		m.put("availability", s.availability().name());
		m.put("storeName", s.location().storeName());
		m.put("address", String.join(", ", s.location().addressLine(), s.location().city(), s.location().region(), s.location().country()));
		m.put("phone", s.location().phone());
		m.put("openDays", s.location().schedule().stream().filter(d -> d.open()).map(d -> d.day() + ":" + d.from() + "-" + d.to()).toList());
		m.put("holdPeriodDays", s.holdPeriodDays());
		m.put("version", s.version());
		return m;
	}
}
