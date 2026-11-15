package com.anadepot.core.catalog.model;

/**
 * How a product is sold. Mirrors the frontend list. The FINAL list is an open business decision, and there are NO
 * pack-to-unit or box-to-unit conversions: a "box" is simply the unit customers buy and stock is counted in.
 * The database stores the code as plain text (no CHECK), so extending the list needs no schema migration.
 */
public enum SellingUnit {
	UNIT, PAIR, PACK, BOX, SET, METER, KILOGRAM
}
