package com.anadepot.infra.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "store_settings")
class StoreSettingEntity {

	@Id
	String settingKey;
	@Column(columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	String value;
	@Version
	Long version;
	UUID updatedBy;
	Instant updatedAt;

	protected StoreSettingEntity() {
	}
}
