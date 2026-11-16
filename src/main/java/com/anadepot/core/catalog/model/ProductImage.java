package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.LocalizedText;
import java.util.UUID;

/**
 * {@code position} 0 is the primary image (same rule as the storefront). {@code url} is where the browser loads it from.
 * {@code objectKey} is the stored object behind it (null for an image that points at an external URL): removing the image deletes exactly that object.
 */
public record ProductImage(UUID id, String url, LocalizedText alt, int position, String objectKey) {

	public ProductImage(UUID id, String url, LocalizedText alt, int position) {
		this(id, url, alt, position, null);
	}

	public ProductImage atPosition(int next) {
		return new ProductImage(id, url, alt, next, objectKey);
	}
}
