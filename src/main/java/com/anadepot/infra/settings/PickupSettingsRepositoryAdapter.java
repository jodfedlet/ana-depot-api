package com.anadepot.infra.settings;

import com.anadepot.core.settings.model.PickupSettings;
import com.anadepot.core.settings.repository.PickupSettingsRepository;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.infra.persistence.Json;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class PickupSettingsRepositoryAdapter implements PickupSettingsRepository {

	private static final String KEY = "pickup";

	private final StoreSettingJpaRepository jpa;

	PickupSettingsRepositoryAdapter(StoreSettingJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public Optional<PickupSettings> find() {
		return jpa.findById(KEY).map(PickupSettingsRepositoryAdapter::toDomain);
	}

	@Override
	public PickupSettings save(PickupSettings settings, Long expectedVersion) {
		var entity = jpa.findById(KEY).orElse(null);
		if (entity == null) {
			if (expectedVersion != null) {
				throw conflict();
			}
			entity = new StoreSettingEntity();
			entity.settingKey = KEY;
		} else if (!Objects.equals(entity.version, expectedVersion)) {
			throw conflict();
		}
		// stored JSON carries no audit metadata: those are real columns
		entity.value = Json.write(PickupMapper.dto(settings.withAudit(null, null, null)));
		entity.updatedBy = settings.updatedBy();
		entity.updatedAt = settings.updatedAt();
		return toDomain(jpa.saveAndFlush(entity));
	}

	private static DomainRuleViolation conflict() {
		return new DomainRuleViolation("concurrent_update", "The pickup settings were changed by someone else. Reload and try again.");
	}

	private static PickupSettings toDomain(StoreSettingEntity e) {
		var dto = Json.read(e.value, PickupDtos.PickupSettingsDto.class);
		return PickupMapper.domain(dto).withAudit(e.version, e.updatedAt, e.updatedBy);
	}
}
