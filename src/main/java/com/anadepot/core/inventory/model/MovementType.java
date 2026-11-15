package com.anadepot.core.inventory.model;

public enum MovementType {
	/** Opening balance when the item is registered. */
	INITIAL,
	ADD,
	REDUCE,
	/** Physical count: sets the on-hand quantity to the counted value. */
	RECOUNT,
	/** Stock handed over when a reservation is collected. */
	SALE
}
