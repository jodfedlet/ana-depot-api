package com.anadepot.infra.catalog;

import static com.anadepot.infra.DatabaseAssertions.assertRejected;
import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.unique;
import static com.anadepot.testsupport.TestData.variant;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.testsupport.TestData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/** The PostgreSQL schema refuses invalid data on its own, whatever the application does. */
class CatalogConstraintsIntegrationTest extends AbstractIntegrationTest {

	/** Test-only view of product_prices with the currency as free text, to prove the database rejects unsupported currencies. */
	@Entity
	@Table(name = "product_prices")
	static class RawPrice {
		@Id UUID id;
		@Column(name = "product_id") UUID productId;
		String currency;
		BigDecimal retailPrice;
		int minOrderQuantity = 1;
		boolean wholesaleEnabled;
		boolean active = true;
		Instant createdAt = Instant.now();
		Instant updatedAt = Instant.now();
	}

	@Autowired PlatformTransactionManager tm;
	@Autowired ProductJpaRepository products;
	@Autowired jakarta.persistence.EntityManager em;

	private TestData.CategoryFixture fx;
	private com.anadepot.core.catalog.model.Product saved;

	@BeforeEach
	void aValidProduct() {
		fx = TestData.category();
		catalog.saveCategory(fx.category());
		saved = catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE, List.of(),
				price(htg("1000.00"), null, 1, tier(5, htg("900.00")))), staff);
	}

	private ProductEntity entity() {
		return products.findById(saved.id()).orElseThrow();
	}

	private ProductPriceEntity htgPrice(ProductEntity p) {
		return p.prices.getFirst();
	}

	// ---------------------------------------------------------------- price checks

	@Test
	void salePriceMustBeBelowRetail() {
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			htgPrice(p).salePrice = new BigDecimal("1000.00");
			products.saveAndFlush(p);
		});
	}

	@Test
	void retailPriceMinimumOrderAndTierValuesMustBeValid() {
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			htgPrice(p).retailPrice = BigDecimal.ZERO;
			products.saveAndFlush(p);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			htgPrice(p).minOrderQuantity = 0;
			products.saveAndFlush(p);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			p.tiers.getFirst().minQuantity = 1;
			products.saveAndFlush(p);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			p.tiers.getFirst().unitPrice = BigDecimal.ZERO;
			products.saveAndFlush(p);
		});
	}

	@Test
	void aProductCannotHaveTwoPriceRowsForTheSameCurrency() {
		assertRejected(tm, "duplicate key", () -> {
			var p = entity();
			var again = new ProductPriceEntity();
			again.id = UUID.randomUUID();
			again.product = p;
			again.currency = SupportedCurrency.HTG;
			again.retailPrice = new BigDecimal("2000.00");
			again.minOrderQuantity = 1;
			again.active = true;
			again.createdAt = Instant.now();
			again.updatedAt = Instant.now();
			p.prices.add(again);
			products.saveAndFlush(p);
		});
	}

	@Test
	void anUnsupportedCurrencyIsRejectedByTheCurrenciesTable() {
		assertRejected(tm, "violates foreign key constraint", () -> {
			var raw = new RawPrice();
			raw.id = UUID.randomUUID();
			raw.productId = saved.id();
			raw.currency = "EUR";
			raw.retailPrice = new BigDecimal("10.00");
			em.persist(raw);
			em.flush();
		});
	}

	// ---------------------------------------------------------------- relationships

	@Test
	void aWholesaleTierNeedsThePriceOfItsOwnCurrency() {
		assertRejected(tm, "violates foreign key constraint", () -> {
			var p = entity();
			var t = new WholesaleTierEntity();
			t.id = UUID.randomUUID();
			t.product = p;
			t.currency = SupportedCurrency.USD;        // the product has NO USD price
			t.minQuantity = 5;
			t.unitPrice = new BigDecimal("9.00");
			p.tiers.add(t);
			products.saveAndFlush(p);
		});
	}

	@Test
	void twoTiersCannotShareAQuantityWithinOneProductAndCurrency() {
		assertRejected(tm, "duplicate key", () -> {
			var p = entity();
			var t = new WholesaleTierEntity();
			t.id = UUID.randomUUID();
			t.product = p;
			t.currency = SupportedCurrency.HTG;
			t.minQuantity = 5;                         // already used by the existing tier
			t.unitPrice = new BigDecimal("800.00");
			p.tiers.add(t);
			products.saveAndFlush(p);
		});
	}

	@Test
	void aVariantPriceOnlyExistsInACurrencyTheProductIsPricedIn() {
		var withVariant = catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE,
				List.of(variant(unique("V"), Map.of(fx.sizeAttribute(), fx.sizeM()), Map.of())), price(htg("1000.00"), null, 1)), staff);
		assertRejected(tm, "violates foreign key constraint", () -> {
			var p = products.findById(withVariant.id()).orElseThrow();
			var v = p.variants.getFirst();
			var vp = new VariantPriceEntity();
			vp.id = UUID.randomUUID();
			vp.variant = v;
			vp.productId = p.id;
			vp.currency = SupportedCurrency.USD;       // no USD price on the product
			vp.retailPrice = new BigDecimal("12.00");
			v.prices.add(vp);
			products.saveAndFlush(p);
		});
	}

	@Test
	void aVariantPriceCannotPointAtAnotherProduct() {
		var other = catalog.saveProduct(product(fx.category().id(), ProductStatus.ACTIVE, price(htg("50.00"), null, 1)), staff);
		var withVariant = catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE,
				List.of(variant(unique("V"), Map.of(), Map.of())), price(htg("1000.00"), null, 1)), staff);
		assertRejected(tm, "violates foreign key constraint", () -> {
			var p = products.findById(withVariant.id()).orElseThrow();
			var v = p.variants.getFirst();
			var vp = new VariantPriceEntity();
			vp.id = UUID.randomUUID();
			vp.variant = v;
			vp.productId = other.id();                  // the variant belongs to p, not to "other"
			vp.currency = SupportedCurrency.HTG;
			vp.retailPrice = new BigDecimal("12.00");
			v.prices.add(vp);
			products.saveAndFlush(p);
		});
	}

	@Test
	void anAttributeOptionMustBelongToTheStatedAttribute() {
		assertRejected(tm, "violates foreign key constraint", () -> {
			var p = entity();
			var value = new ProductAttributeValueEntity();
			value.id = UUID.randomUUID();
			value.product = p;
			value.attributeId = fx.materialAttribute();   // the free-text attribute...
			value.optionId = fx.sizeS();                  // ...with an option of the size attribute
			p.attributeValues.add(value);
			products.saveAndFlush(p);
		});
	}

	@Test
	void anAttributeValueHoldsExactlyOneKindOfValue() {
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			var value = new ProductAttributeValueEntity();
			value.id = UUID.randomUUID();
			value.product = p;
			value.attributeId = fx.sizeAttribute();
			value.optionId = fx.sizeS();
			value.textValue = "and also text";
			p.attributeValues.add(value);
			products.saveAndFlush(p);
		});
	}

	// ---------------------------------------------------------------- uniqueness & lifecycle

	@Test
	void skusAreUniqueAcrossProductsAndVariants() {
		var sku = unique("SHARED");
		catalog.saveProduct(product(fx.category().id(), sku, ProductStatus.ACTIVE, List.of(variant(unique("VS"), Map.of(), Map.of())), price(htg("10.00"), null, 1)), staff);
		assertRejected(tm, "duplicate key", () -> {
			var other = new ProductEntity();
			other.id = UUID.randomUUID();
			other.sku = sku;
			other.slug = unique("slug");
			other.categoryId = fx.category().id();
			other.name = Map.of("fr", "x");
			other.description = Map.of();
			other.unitNote = Map.of();
			other.sellingUnit = com.anadepot.core.catalog.model.SellingUnit.UNIT;
			other.status = ProductStatus.DRAFT;
			other.createdAt = Instant.now();
			other.updatedAt = Instant.now();
			products.saveAndFlush(other);
		});
		var variantSku = unique("VSKU");
		catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE, List.of(variant(variantSku, Map.of(), Map.of())), price(htg("10.00"), null, 1)), staff);
		assertRejected(tm, "duplicate key", () -> catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE,
				List.of(variant(variantSku, Map.of(), Map.of())), price(htg("10.00"), null, 1)), staff));
	}

	@Test
	void anActiveProductMustHaveASku() {
		assertRejected(tm, "violates check constraint", () -> {
			var p = entity();
			p.sku = null;
			products.saveAndFlush(p);
		});
	}

	@Test
	void onlyChoiceAttributesCanFilterOrDefineVariants() {
		assertRejected(tm, "violates check constraint", () -> {
			var categories = em.find(CategoryEntity.class, fx.category().id());
			var text = categories.attributes.stream().filter(a -> a.attrKey.equals("material")).findFirst().orElseThrow();
			text.filterable = true;
			em.flush();
		});
	}

	@Test
	void twoImagesCannotShareAPositionOnOneProduct() {
		assertRejected(tm, "duplicate key", () -> {
			var p = entity();
			for (int i = 0; i < 2; i++) {
				var img = new ProductImageEntity();
				img.id = UUID.randomUUID();
				img.product = p;
				img.url = "https://cdn.test/" + i;
				img.alt = Map.of();
				img.position = 0;
				p.images.add(img);
			}
			products.saveAndFlush(p);
		});
	}
}
