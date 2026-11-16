package com.anadepot.infra.inventory;

import static com.anadepot.infra.DatabaseAssertions.assertRejected;
import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static com.anadepot.testsupport.TestData.unique;
import static com.anadepot.testsupport.TestData.variant;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.inventory.model.StockReason;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class InventoryConstraintsIntegrationTest extends AbstractIntegrationTest {

	@Autowired PlatformTransactionManager tm;
	@Autowired InventoryItemJpaRepository items;
	@Autowired InventoryMovementJpaRepository movements;

	private InventoryItemEntity newItem(UUID productId, UUID variantId) {
		var e = new InventoryItemEntity();
		e.id = UUID.randomUUID();
		e.productId = productId;
		e.variantId = variantId;
		e.tracked = true;
		e.onHand = 1;
		e.createdAt = Instant.now();
		e.updatedAt = Instant.now();
		return e;
	}

	@Test
	void stockQuantitiesCannotBeNegative() {
		var product = stockedMattress(5);
		assertRejected(tm, "violates check constraint", () -> {
			var e = items.findForProduct(product.id()).orElseThrow();
			e.onHand = -1;
			items.saveAndFlush(e);
		});
		assertRejected(tm, "violates check constraint", () -> {
			var e = items.findForProduct(product.id()).orElseThrow();
			e.reserved = -1;
			items.saveAndFlush(e);
		});
	}

	@Test
	void thereIsExactlyOneStockPositionPerProductAndPerVariant_notPerCurrency() {
		var fx = TestData.category();
		catalog.saveCategory(fx.category());
		var withVariant = catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE,
				List.of(variant(unique("V"), Map.of(), Map.of())), price(htg("10.00"), null, 1)), staff);
		var plain = stockedMattress(3);
		// a second product-level position for the same product (e.g. "one per currency") is impossible
		assertRejected(tm, "duplicate key", () -> items.saveAndFlush(newItem(plain.id(), null)));
		// likewise a second position for the same variant
		inventory.register(withVariant.id(), withVariant.variants().getFirst().id(), true, 2, null, null);
		assertRejected(tm, "duplicate key", () -> items.saveAndFlush(newItem(withVariant.id(), withVariant.variants().getFirst().id())));
		// and a variant position must belong to its own product (a variant that has no stock row yet, so only the FK can object)
		var other = catalog.saveProduct(product(fx.category().id(), unique("SKU"), ProductStatus.ACTIVE,
				List.of(variant(unique("V"), Map.of(), Map.of())), price(htg("10.00"), null, 1)), staff);
		assertRejected(tm, "violates foreign key constraint", () -> items.saveAndFlush(newItem(plain.id(), other.variants().getFirst().id())));
	}

	@Test
	void anUntrackedItemCannotHoldQuantities() {
		var plain = stockedMattress(3);
		assertRejected(tm, "violates check constraint", () -> {
			var e = items.findForProduct(plain.id()).orElseThrow();
			e.tracked = false;               // still has on_hand = 3
			items.saveAndFlush(e);
		});
	}

	@Test
	void aSaleMovementNeedsAReservationAndMovementsAreAppendOnly() {
		var plain = stockedMattress(3);
		var item = items.findForProduct(plain.id()).orElseThrow();
		inventory.adjust(plain.id(), null, MovementType.ADD, 1, StockReason.RECEPTION, null, staff.userId(), key());
		assertRejected(tm, "violates check constraint", () -> {
			var m = new InventoryMovementEntity();
			m.id = UUID.randomUUID();
			m.inventoryItemId = item.id;
			m.movementType = MovementType.SALE;     // a SALE without the reservation that caused it
			m.delta = -1;
			m.balanceAfter = 3;
			m.reason = StockReason.SALE;
			m.createdAt = Instant.now();
			movements.saveAndFlush(m);
		});
		var existing = movements.findByInventoryItemIdOrderByCreatedAtAscIdAsc(item.id).getFirst();
		assertRejected(tm, "append-only", () -> {
			var m = movements.findById(existing.id).orElseThrow();
			m.note = "rewritten";
			movements.saveAndFlush(m);
		});
		assertRejected(tm, "append-only", () -> {
			movements.deleteById(existing.id);
			movements.flush();
		});
	}
}
