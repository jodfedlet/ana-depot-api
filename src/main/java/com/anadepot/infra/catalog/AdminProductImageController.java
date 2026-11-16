package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.model.ProductImage;
import com.anadepot.core.catalog.service.ProductImageService;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.infra.security.Actors;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff-only product images. Adding an image is a three-step flow so the file never passes through this server: (1) request an upload ticket (a presigned PUT URL for
 * ONE object whose key the server generates), (2) the browser PUTs the file to object storage, (3) complete: the server inspects what was really stored and only then
 * attaches it to the product. Removing an image deletes its stored object. Every route is under {@code /api/v1/admin} (staff authority required by the security chain).
 */
@RestController
@RequestMapping("/api/v1/admin/products/{productId}/images")
class AdminProductImageController {

	record UploadRequest(String contentType, Long sizeBytes) {
	}

	record UploadTarget(String url, String method, Map<String, String> headers, Instant expiresAt) {
	}

	record UploadTicketResponse(UUID uploadId, UUID productId, String contentType, long maxBytes, UploadTarget upload) {
	}

	record OrderRequest(List<UUID> imageIds) {
	}

	record ImageList(List<ProductDtos.ImageResponse> items) {
	}

	private final UploadService uploads;
	private final ProductImageService images;

	AdminProductImageController(UploadService uploads, ProductImageService images) {
		this.uploads = uploads;
		this.images = images;
	}

	@PostMapping("/uploads")
	ResponseEntity<UploadTicketResponse> requestUpload(@PathVariable UUID productId, @RequestBody UploadRequest body,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication) {
		var issues = new java.util.ArrayList<FieldIssue>();
		if (body == null || body.contentType() == null || body.contentType().isBlank()) {
			issues.add(new FieldIssue("contentType", "required"));
		}
		if (body == null || body.sizeBytes() == null) {
			issues.add(new FieldIssue("sizeBytes", "required"));
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		var ticket = uploads.requestProductImage(productId, body.contentType(), body.sizeBytes(), Actors.staff(authentication), idempotencyKey);
		var u = ticket.upload();
		return ResponseEntity.status(HttpStatus.CREATED).body(new UploadTicketResponse(ticket.intent().id(), productId, ticket.intent().declaredContentType(), ticket.intent().maxBytes(),
				new UploadTarget(u.url(), u.method(), u.headers(), u.expiresAt())));
	}

	/** Verifies what was actually uploaded, then attaches it. Repeating the call after success returns the same image. */
	@PostMapping("/uploads/{uploadId}/complete")
	ProductDtos.ImageResponse complete(@PathVariable UUID productId, @PathVariable UUID uploadId, Authentication authentication) {
		var intent = uploads.completeProductImage(productId, uploadId, Actors.staff(authentication));      // an upload of another product looks like a missing one
		return images.list(productId).stream().filter(i -> intent.objectKey().equals(i.objectKey())).findFirst().map(AdminProductImageController::response)
				.orElseThrow(() -> new NotFoundException("Image", uploadId));
	}

	@GetMapping
	ImageList list(@PathVariable UUID productId) {
		return new ImageList(images.list(productId).stream().map(AdminProductImageController::response).toList());
	}

	/** The new order: every current image id exactly once; the first becomes the primary image. */
	@PutMapping("/order")
	ImageList reorder(@PathVariable UUID productId, @RequestBody OrderRequest body, Authentication authentication) {
		if (body == null || body.imageIds() == null) {
			throw new FieldValidationException(List.of(new FieldIssue("imageIds", "required")));
		}
		return new ImageList(images.reorder(productId, body.imageIds(), Actors.staff(authentication)).stream().map(AdminProductImageController::response).toList());
	}

	@DeleteMapping("/{imageId}")
	ResponseEntity<Void> remove(@PathVariable UUID productId, @PathVariable UUID imageId, Authentication authentication) {
		images.remove(productId, imageId, Actors.staff(authentication));
		return ResponseEntity.noContent().build();
	}

	private static ProductDtos.ImageResponse response(ProductImage i) {
		return new ProductDtos.ImageResponse(i.id(), i.url(), i.alt().values(), i.position());
	}
}
