package com.anadepot.core.inventory.model;

/** Why stock changed. Same list as the storefront's inventory screen; stored as text so it can grow without a migration. */
public enum StockReason {
	RECEPTION, RETURN, DAMAGE, LOSS, CORRECTION, COUNT, SALE, OTHER
}
