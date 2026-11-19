package com.anadepot.core.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.staff.model.Permission;
import com.anadepot.core.staff.model.StaffRole;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The owner-approved D14 table, restated here on purpose: changing a role's rights must be a deliberate edit of THIS test too. */
class StaffRoleTest {

	@Test
	void ownerHasEverything() {
		assertThat(StaffRole.OWNER.permissions()).containsExactlyInAnyOrder(Permission.values());
	}

	@Test
	void managerHasEverythingExceptSettingsAndTeam() {
		Set<Permission> expected = EnumSet.allOf(Permission.class);
		expected.remove(Permission.SETTINGS_MANAGE);
		expected.remove(Permission.TEAM_MANAGE);
		assertThat(StaffRole.MANAGER.permissions()).isEqualTo(expected);
	}

	@Test
	void counterStaffWorkReservationsAndCashAndReadStockCustomersAndMessages() {
		assertThat(StaffRole.STAFF.permissions()).containsExactlyInAnyOrder(Permission.RESERVATIONS, Permission.PAYMENTS_RECORD, Permission.INVENTORY_VIEW, Permission.CUSTOMERS_VIEW, Permission.NOTIFICATIONS_VIEW);
		assertThat(StaffRole.STAFF.allows(Permission.PAYMENTS_CORRECT)).isFalse();
		assertThat(StaffRole.STAFF.allows(Permission.CATALOG_MANAGE)).isFalse();
		assertThat(StaffRole.STAFF.allows(Permission.REPORTS_VIEW)).isFalse();
		assertThat(StaffRole.STAFF.allows(Permission.INVENTORY_ADJUST)).isFalse();
		assertThat(StaffRole.STAFF.allows(Permission.CUSTOMERS_EDIT)).isFalse();
	}
}
