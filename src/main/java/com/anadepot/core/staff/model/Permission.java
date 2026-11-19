package com.anadepot.core.staff.model;

/** One thing a staff member may do. The role-to-permission table is {@link StaffRole}; the HTTP rules that enforce each permission are in {@code infra.security.AdminAccessRules}. */
public enum Permission {
	/** View and work reservations: accept, reject, ready, collect, cancel, notes. */
	RESERVATIONS,
	/** Record a cash payment; read a reservation's payments, ledger and reconciliation; see online payment attempts. */
	PAYMENTS_RECORD,
	/** Reverse or adjust a payment. */
	PAYMENTS_CORRECT,
	INVENTORY_VIEW,
	/** Adjust stock (reason mandatory), create stock positions, change thresholds. */
	INVENTORY_ADJUST,
	/** Products, categories, prices, images and the search index. */
	CATALOG_MANAGE,
	CUSTOMERS_VIEW,
	CUSTOMERS_EDIT,
	/** Reports and the global payment lists: they carry money figures. */
	REPORTS_VIEW,
	NOTIFICATIONS_VIEW,
	NOTIFICATIONS_RESEND,
	/** Pickup settings and store settings. */
	SETTINGS_MANAGE,
	/** Invite, remove, restore team members and change their role. */
	TEAM_MANAGE
}
