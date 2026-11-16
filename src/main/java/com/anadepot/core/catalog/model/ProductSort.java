package com.anadepot.core.catalog.model;

public enum ProductSort {
	/** Full-text relevance (only meaningful with a search text; otherwise equals NEWEST). */
	RELEVANCE, NEWEST, NAME, PRICE_ASC, PRICE_DESC
}
