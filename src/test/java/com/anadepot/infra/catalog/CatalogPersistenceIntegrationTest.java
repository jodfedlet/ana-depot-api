package com.anadepot.infra.catalog;

import static com.anadepot.testsupport.TestData.category;
import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static com.anadepot.testsupport.TestData.tier;
import static com.anadepot.testsupport.TestData.usd;
import static com.anadepot.testsupport.TestData.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.money.SupportedCurrency;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real PostgreSQL + Flyway migrations + Hibernate schema validation: the model persists and reloads intact. */
class CatalogPersistenceIntegrationTest extends AbstractIntegrationTest {

	@Autowired AuditEventJpaRepository audit;

	@Test
	void oneProductHoldsIndependentHtgAndUsdPricesWithoutDuplicatingTheProduct() {
		var fx = category();
		catalog.saveCategory(fx.category());
		var saved = catalog.saveProduct(product(fx.category().id(), ProductStatus.ACTIVE,
				price(htg("25000.00"), null, 1, tier(5, htg("23000.00"))),
				price(usd("180.00"), usd("170.00"), 2, tier(5, usd("160.00")), tier(10, usd("150.00")))), staff);

		var loaded = catalog.getProduct(saved.id());

		assertThat(loaded.pricing().all()).hasSize(2);
		var h = loaded.pricing().price(SupportedCurrency.HTG).orElseThrow();
		var u = loaded.pricing().price(SupportedCurrency.USD).orElseThrow();
		assertThat(h.retail()).isEqualTo(htg("25000.00"));
		assertThat(h.sale()).isNull();
		assertThat(h.minOrderQuantity()).isEqualTo(1);
		assertThat(h.tiers()).hasSize(1);
		assertThat(u.retail()).isEqualTo(usd("180.00"));
		assertThat(u.sale()).isEqualTo(usd("170.00"));
		assertThat(u.minOrderQuantity()).isEqualTo(2);
		assertThat(u.tiers()).extracting(t -> t.minQuantity() + ":" + t.unitPrice()).containsExactly("5:160.00 USD", "10:150.00 USD");
		// the tiers are independent: HTG has no 10+ tier, USD has two
		assertThat(h.tiers()).extracting(t -> t.minQuantity()).doesNotContain(10);
	}

	@Test
	void aProductCanBeSoldInHtgOnlyOrUsdOnly() {
		var fx = category();
		catalog.saveCategory(fx.category());
		var htgOnly = catalog.saveProduct(product(fx.category().id(), ProductStatus.ACTIVE, price(htg("1000.00"), null, 1)), staff);
		var usdOnly = catalog.saveProduct(product(fx.category().id(), ProductStatus.ACTIVE, price(usd("9.50"), null, 1)), staff);

		assertThat(catalog.getProduct(htgOnly.id()).pricing().price(SupportedCurrency.USD)).isEmpty();
		assertThat(catalog.getProduct(usdOnly.id()).pricing().price(SupportedCurrency.HTG)).isEmpty();
		// a missing currency is never generated or converted: quoting it is an error
		assertThatThrownBy(() -> catalog.quote(htgOnly.id(), null, SupportedCurrency.USD, 1))
				.isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("price_not_available");
		assertThat(catalog.quote(usdOnly.id(), null, SupportedCurrency.USD, 1).unitPrice()).isEqualTo(usd("9.50"));
	}

	@Test
	void updatingOnePriceLeavesTheOtherCurrencyUntouchedAndIsAudited() {
		var fx = category();
		catalog.saveCategory(fx.category());
		var original = catalog.saveProduct(product(fx.category().id(), ProductStatus.ACTIVE, price(htg("25000.00"), null, 1), price(usd("180.00"), null, 1)), staff);

		var changed = new com.anadepot.core.catalog.model.Product(original.id(), original.sku(), original.slug(), original.categoryId(), original.name(), original.description(),
				original.brand(), original.unit(), original.unitNote(), original.status(),
				com.anadepot.core.catalog.model.ProductPricing.of(java.util.List.of(original.pricing().price(SupportedCurrency.HTG).orElseThrow(), price(usd("200.00"), null, 1))),
				original.variants(), original.attributes(), original.images(), original.createdAt(), original.updatedAt());
		catalog.saveProduct(changed, staff);

		var reloaded = catalog.getProduct(original.id());
		assertThat(reloaded.pricing().price(SupportedCurrency.HTG).orElseThrow().retail()).isEqualTo(htg("25000.00"));
		assertThat(reloaded.pricing().price(SupportedCurrency.USD).orElseThrow().retail()).isEqualTo(usd("200.00"));
		var events = audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", original.id().toString());
		assertThat(events).filteredOn(e -> e.getAction().equals("PRODUCT_PRICE_CHANGED")).hasSize(3); // 2 initial prices + the USD change
		var last = events.stream().filter(e -> e.getAction().equals("PRODUCT_PRICE_CHANGED")).reduce((a, b) -> b).orElseThrow();
		assertThat(last.getActorUserId()).isEqualTo(staff.userId());
		assertThat(last.getPreviousValues()).contains("180.00");
		assertThat(last.getNewValues()).contains("200.00");
	}

	@Test
	void variantsKeepTheirOwnPricesPerCurrencyAndAttributesRoundTrip() {
		var fx = category();
		catalog.saveCategory(fx.category());
		var v = variant(com.anadepot.testsupport.TestData.unique("V"), Map.of(fx.sizeAttribute(), fx.sizeL()), Map.of(SupportedCurrency.HTG, htg("30000.00"), SupportedCurrency.USD, usd("210.00")));
		var saved = catalog.saveProduct(product(fx.category().id(), com.anadepot.testsupport.TestData.unique("SKU"), ProductStatus.ACTIVE, java.util.List.of(v),
				price(htg("25000.00"), null, 1, tier(5, htg("23000.00"))), price(usd("180.00"), null, 1)), staff);

		var loaded = catalog.getProduct(saved.id());
		var lv = loaded.variants().getFirst();
		assertThat(lv.retailOverride(SupportedCurrency.HTG)).isEqualTo(htg("30000.00"));
		assertThat(lv.retailOverride(SupportedCurrency.USD)).isEqualTo(usd("210.00"));
		assertThat(lv.axisValues()).containsEntry(fx.sizeAttribute(), fx.sizeL());
		// the tier scales with the variant's own HTG price: 23000 * 30000 / 25000 = 27600.00
		assertThat(catalog.quote(saved.id(), lv.id(), SupportedCurrency.HTG, 5).unitPrice()).isEqualTo(htg("27600.00"));
	}

	@Test
	void duplicateSkuIsRejectedAndInactiveProductsCannotBeQuoted() {
		var fx = category();
		catalog.saveCategory(fx.category());
		var sku = com.anadepot.testsupport.TestData.unique("DUP");
		catalog.saveProduct(product(fx.category().id(), sku, ProductStatus.ACTIVE, java.util.List.of(), price(htg("10.00"), null, 1)), staff);
		assertThatThrownBy(() -> catalog.saveProduct(product(fx.category().id(), sku, ProductStatus.ACTIVE, java.util.List.of(), price(htg("10.00"), null, 1)), staff))
				.isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("duplicate_sku");
		var inactive = catalog.saveProduct(product(fx.category().id(), ProductStatus.INACTIVE, price(htg("10.00"), null, 1)), staff);
		assertThatThrownBy(() -> catalog.quote(inactive.id(), null, SupportedCurrency.HTG, 1)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("product_unavailable");
	}
}
