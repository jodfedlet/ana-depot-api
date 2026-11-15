package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.LocalizedText;
import java.util.UUID;

/** {@code position} 0 is the primary image (same rule as the storefront). {@code url} points at object storage, later. */
public record ProductImage(UUID id, String url, LocalizedText alt, int position) {
}
