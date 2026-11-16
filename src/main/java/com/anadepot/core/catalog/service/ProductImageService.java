package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.ProductImage;
import com.anadepot.core.shared.Actor;
import java.util.List;
import java.util.UUID;

/**
 * Managing the images a product already has (adding goes through {@link UploadService}: request a ticket, upload, complete).
 * Which staff may do this is decided at the API boundary.
 */
public interface ProductImageService {

	/** Every image of the product in display order (position 0 is the primary image). */
	List<ProductImage> list(UUID productId);

	/** New display order: {@code orderedImageIds} must be every current image exactly once, else {@code images_order_invalid}. */
	List<ProductImage> reorder(UUID productId, List<UUID> orderedImageIds, Actor actor);

	/** Removes the image and DELETES the stored object behind it (never any other object). The remaining images are renumbered. */
	void remove(UUID productId, UUID imageId, Actor actor);
}
