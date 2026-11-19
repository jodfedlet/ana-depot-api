package com.anadepot.core.staff.model;

import java.util.EnumSet;
import java.util.Set;

/** The three fixed roles (D14, owner-approved). There are no custom roles: the table below is the whole model. */
public enum StaffRole {
	OWNER(EnumSet.allOf(Permission.class)),
	MANAGER(EnumSet.of(Permission.RESERVATIONS, Permission.PAYMENTS_RECORD, Permission.PAYMENTS_CORRECT, Permission.INVENTORY_VIEW, Permission.INVENTORY_ADJUST,
			Permission.CATALOG_MANAGE, Permission.CUSTOMERS_VIEW, Permission.CUSTOMERS_EDIT, Permission.REPORTS_VIEW, Permission.NOTIFICATIONS_VIEW, Permission.NOTIFICATIONS_RESEND)),
	STAFF(EnumSet.of(Permission.RESERVATIONS, Permission.PAYMENTS_RECORD, Permission.INVENTORY_VIEW, Permission.CUSTOMERS_VIEW, Permission.NOTIFICATIONS_VIEW));

	private final Set<Permission> permissions;

	StaffRole(Set<Permission> permissions) {
		this.permissions = Set.copyOf(permissions);
	}

	public Set<Permission> permissions() {
		return permissions;
	}

	public boolean allows(Permission permission) {
		return permissions.contains(permission);
	}
}
