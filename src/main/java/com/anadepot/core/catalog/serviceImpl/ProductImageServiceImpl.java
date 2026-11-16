package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.ProductImage;
import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.service.ProductImageService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainEvent;
import com.anadepot.core.shared.DomainEventRecorder;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.OperationMetrics;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ProductImageServiceImpl implements ProductImageService {

	private final ProductRepository products;
	private final ObjectStorage storage;
	private final AuditRecorder audit;
	private final DomainEventRecorder events;
	private final OperationMetrics metrics;
	private final TransactionBoundary tx;
	private final Clock clock;

	public ProductImageServiceImpl(ProductRepository products, ObjectStorage storage, AuditRecorder audit, DomainEventRecorder events, OperationMetrics metrics,
			TransactionBoundary tx, Clock clock) {
		this.products = products;
		this.storage = storage;
		this.audit = audit;
		this.events = events;
		this.metrics = metrics;
		this.tx = tx;
		this.clock = clock;
	}

	@Override
	public List<ProductImage> list(UUID productId) {
		return products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId)).images();
	}

	@Override
	public List<ProductImage> reorder(UUID productId, List<UUID> orderedImageIds, Actor actor) {
		return tx.inTransaction(() -> {
			var product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
			var now = clock.instant();
			var saved = products.save(product.withImageOrder(orderedImageIds, now));
			audit.record(AuditEvent.of("PRODUCT_IMAGES_REORDERED", "product", productId, actor, Map.of("order", product.images().stream().map(i -> i.id().toString()).toList()),
					Map.of("order", orderedImageIds.stream().map(UUID::toString).toList()), null));
			return saved.images();
		});
	}

	@Override
	public void remove(UUID productId, UUID imageId, Actor actor) {
		var removed = tx.inTransaction(() -> {
			var product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
			var image = product.images().stream().filter(i -> i.id().equals(imageId)).findFirst().orElseThrow(() -> new NotFoundException("Image", imageId));
			var now = clock.instant();
			products.save(product.withoutImage(imageId, now));
			audit.record(AuditEvent.of("PRODUCT_IMAGE_REMOVED", "product", productId, actor, Map.of("imageId", imageId.toString(), "objectKey", String.valueOf(image.objectKey())), Map.of(), null));
			events.record(new DomainEvent("product.image.removed", "product", productId, now.toEpochMilli(), Map.of("imageId", imageId.toString())));
			return image;
		});
		if (removed.objectKey() == null) {
			return;       // an external URL: there is no object of ours to delete
		}
		try {
			storage.delete(removed.objectKey());          // outside the transaction; deleting a missing object is not an error
		} catch (RuntimeException e) {
			// the image is already gone from the product; the orphaned object is recorded so it can be cleaned up, and the request still succeeds
			metrics.count("product.image.object_delete_failed");
			tx.inTransaction(() -> audit.record(AuditEvent.of("PRODUCT_IMAGE_OBJECT_DELETE_FAILED", "product", productId, actor, Map.of(),
					Map.of("objectKey", removed.objectKey(), "error", e.getClass().getSimpleName()), null)));
		}
	}
}
