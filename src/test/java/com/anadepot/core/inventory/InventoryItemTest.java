package com.anadepot.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.MovementType;
import com.anadepot.core.shared.DomainRuleViolation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryItemTest {

	private InventoryItem item(int onHand, int reserved) {
		return new InventoryItem(UUID.randomUUID(), UUID.randomUUID(), null, true, 3, onHand, reserved);
	}

	@Test
	void availableIsOnHandMinusReserved() {
		assertThat(item(10, 4).available()).isEqualTo(6);
		assertThat(item(10, 0).available()).isEqualTo(10);
	}

	@Test
	void reservingMovesQuantityToReservedWithoutChangingOnHand() {
		var after = item(10, 4).reserve(5);
		assertThat(after.onHand()).isEqualTo(10);
		assertThat(after.reserved()).isEqualTo(9);
		assertThat(after.available()).isEqualTo(1);
	}

	@Test
	void reservingMoreThanAvailableFails() {
		assertThatThrownBy(() -> item(10, 4).reserve(7)).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("insufficient_stock");
		assertThat(item(10, 4).reserve(6).available()).isZero(); // exactly the available quantity is fine
	}

	@Test
	void releasingReturnsReservedQuantity_collectingDeductsPhysicalStockAndReleases() {
		assertThat(item(10, 4).release(4).available()).isEqualTo(10);
		var collected = item(10, 4).handOver(3);
		assertThat(collected.onHand()).isEqualTo(7);
		assertThat(collected.reserved()).isEqualTo(1);
		assertThatThrownBy(() -> item(10, 2).release(3)).extracting("code").isEqualTo("inventory_inconsistent");
	}

	@Test
	void manualAdjustmentsAddReduceAndRecount_neverBelowZero() {
		assertThat(item(10, 0).adjust(MovementType.ADD, 5).onHand()).isEqualTo(15);
		assertThat(item(10, 0).adjust(MovementType.REDUCE, 4).onHand()).isEqualTo(6);
		assertThat(item(10, 3).adjust(MovementType.RECOUNT, 20).onHand()).isEqualTo(20);
		assertThat(item(10, 3).adjust(MovementType.REDUCE, 2).reserved()).isEqualTo(3); // reserved is never touched by an adjustment
		assertThatThrownBy(() -> item(3, 0).adjust(MovementType.REDUCE, 4)).extracting("code").isEqualTo("stock_negative");
		assertThatThrownBy(() -> item(3, 0).adjust(MovementType.ADD, 0)).extracting("code").isEqualTo("stock_invalid");
	}

	@Test
	void physicalLossBelowReservedClampsAvailableToZero_asTheStorefrontDoes() {
		var shrunk = item(10, 8).adjust(MovementType.REDUCE, 5);
		assertThat(shrunk.onHand()).isEqualTo(5);
		assertThat(shrunk.reserved()).isEqualTo(8);
		assertThat(shrunk.available()).isZero();
		assertThat(shrunk.canReserve(1)).isFalse();
	}

	@Test
	void stateReflectsStockAndTheSellersLowStockThreshold() {
		assertThat(item(10, 0).state()).isEqualTo(InventoryItem.StockState.OK);
		assertThat(item(10, 7).state()).isEqualTo(InventoryItem.StockState.LOW);   // available 3 <= threshold 3
		assertThat(item(10, 10).state()).isEqualTo(InventoryItem.StockState.OUT);
		var noThreshold = new InventoryItem(UUID.randomUUID(), UUID.randomUUID(), null, true, null, 10, 9);
		assertThat(noThreshold.state()).isEqualTo(InventoryItem.StockState.OK);    // no policy invented: no threshold, no "low"
	}

	@Test
	void untrackedItemsHaveNoQuantityLimit() {
		var untracked = new InventoryItem(UUID.randomUUID(), UUID.randomUUID(), null, false, null, 0, 0);
		assertThat(untracked.canReserve(1_000)).isTrue();
		assertThat(untracked.reserve(5)).isEqualTo(untracked);
	}
}
