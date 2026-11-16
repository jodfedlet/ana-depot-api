package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.SellingUnit;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
class ProductEntity {

	@Id
	UUID id;
	String sku;
	String slug;
	UUID categoryId;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	Map<String, String> name;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	Map<String, String> description;
	String brand;
	@Enumerated(EnumType.STRING)
	SellingUnit sellingUnit;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	Map<String, String> unitNote;
	@Enumerated(EnumType.STRING)
	ProductStatus status;
	Instant createdAt;
	Instant updatedAt;
	String sortName = "";
	@Version
	Long version;
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	List<ProductPriceEntity> prices = new ArrayList<>();
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	List<WholesaleTierEntity> tiers = new ArrayList<>();
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	List<ProductVariantEntity> variants = new ArrayList<>();
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	List<ProductAttributeValueEntity> attributeValues = new ArrayList<>();
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	List<ProductImageEntity> images = new ArrayList<>();

	protected ProductEntity() {
	}
}
