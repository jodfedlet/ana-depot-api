package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.AttributeType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "category_attribute_definitions")
class AttributeDefinitionEntity {

	@Id
	UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id")
	CategoryEntity category;
	String attrKey;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	Map<String, String> label;
	@Enumerated(EnumType.STRING)
	AttributeType attrType;
	String unit;
	boolean required;
	boolean filterable;
	boolean variantAxis;
	int displayOrder;
	boolean active;
	@OneToMany(mappedBy = "attribute", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder ASC")
	List<AttributeOptionEntity> options = new ArrayList<>();

	protected AttributeDefinitionEntity() {
	}
}
