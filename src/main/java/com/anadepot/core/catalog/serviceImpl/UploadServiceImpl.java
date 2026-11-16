package com.anadepot.core.catalog.serviceImpl;

import com.anadepot.core.catalog.model.ProductImage;
import com.anadepot.core.catalog.model.UploadIntent;
import com.anadepot.core.catalog.model.UploadStatus;
import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.catalog.repository.ProductRepository;
import com.anadepot.core.catalog.repository.UploadIntentRepository;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.Fingerprint;
import com.anadepot.core.shared.IdempotencyService;
import com.anadepot.core.shared.LocalizedText;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.core.shared.TransactionBoundary;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UploadServiceImpl implements UploadService {

	private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
	private static final int HEAD_BYTES = 16;

	private final UploadIntentRepository intents;
	private final ProductRepository products;
	private final ObjectStorage storage;
	private final AuditRecorder audit;
	private final IdempotencyService idempotency;
	private final TransactionBoundary tx;
	private final Clock clock;
	private final Duration urlTtl;
	private final long maxBytes;
	private final List<String> allowedTypes;
	private final Duration abandonedAfter;

	public UploadServiceImpl(UploadIntentRepository intents, ProductRepository products, ObjectStorage storage, AuditRecorder audit,
			IdempotencyService idempotency, TransactionBoundary tx, Clock clock, Duration urlTtl, long maxBytes, List<String> allowedTypes, Duration abandonedAfter) {
		this.intents = intents;
		this.products = products;
		this.storage = storage;
		this.audit = audit;
		this.idempotency = idempotency;
		this.tx = tx;
		this.clock = clock;
		this.urlTtl = urlTtl;
		this.maxBytes = maxBytes;
		this.allowedTypes = allowedTypes;
		this.abandonedAfter = abandonedAfter;
	}

	@Override
	public Ticket requestProductImage(UUID productId, String contentType, long declaredSize, Actor actor, String idempotencyKey) {
		if (actor == null || actor.userId() == null) {
			throw new DomainRuleViolation("authentication_required", "Uploads require an authenticated user");
		}
		var type = contentType == null ? "" : contentType.strip().toLowerCase();
		if (!allowedTypes.contains(type) || !EXTENSIONS.containsKey(type)) {
			throw new DomainRuleViolation("upload_type_not_allowed", "Allowed image types: " + allowedTypes);
		}
		if (declaredSize < 1 || declaredSize > maxBytes) {
			throw new DomainRuleViolation("upload_too_large", "Image size must be between 1 byte and " + maxBytes + " bytes");
		}
		var fingerprint = Fingerprint.of(productId, type, declaredSize);
		var intent = idempotency.run(idempotencyKey, "user:" + actor.userId(), "upload.request", fingerprint, () -> tx.inTransaction(() -> {
			products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
			var now = clock.instant();
			// The key is generated HERE: the client cannot choose, guess or overwrite an object path.
			var key = "product-images/" + productId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(type);
			var saved = intents.save(new UploadIntent(UUID.randomUUID(), productId, key, type, declaredSize, UploadStatus.REQUESTED, now.plus(urlTtl), actor.userId(), null, null, now, null));
			return saved.id();
		}), id -> intents.findById(id).orElseThrow());
		return new Ticket(intent, storage.presignPut(intent.objectKey(), intent.declaredContentType(), intent.maxBytes(), urlTtl));
	}

	/**
	 * Three steps, no storage call inside a database transaction:
	 * (1) short transaction: authorize and check the intent; (2) NO transaction: ask the storage what was really uploaded and
	 * verify it; (3) short transaction: record the verdict (attach the image, or mark REJECTED) under a row lock.
	 * A rejection is committed BEFORE the exception is thrown, so the REJECTED status is never rolled back.
	 */
	@Override
	public UploadIntent completeProductImage(UUID intentId, Actor actor) {
		var pending = tx.inTransaction(() -> {
			var intent = intents.findById(intentId).orElseThrow(() -> new NotFoundException("Upload", intentId));
			if (actor == null || !intent.requestedBy().equals(actor.userId())) {
				throw new NotFoundException("Upload", intentId); // same answer as "does not exist": other people's uploads are not revealed
			}
			if (intent.status() == UploadStatus.VERIFIED) {
				return intent; // repeating completion is harmless
			}
			if (intent.status() != UploadStatus.REQUESTED) {
				throw new DomainRuleViolation("upload_not_pending", "This upload can no longer be completed (" + intent.status() + ")");
			}
			if (clock.instant().isAfter(intent.expiresAt().plus(abandonedAfter))) {
				throw new DomainRuleViolation("upload_expired", "The upload window has expired; request a new upload");
			}
			return intent;
		});
		if (pending.status() == UploadStatus.VERIFIED) {
			return pending;
		}
		var stored = storage.inspect(pending.objectKey(), HEAD_BYTES).orElse(null);   // network call: outside any transaction
		var problem = stored == null ? "upload_missing" : verify(pending, stored);

		var verdict = tx.inTransaction(() -> {
			var intent = intents.findByIdForUpdate(intentId).orElseThrow(() -> new NotFoundException("Upload", intentId));
			if (intent.status() == UploadStatus.VERIFIED) {
				return new Verdict(intent, null); // a concurrent completion won
			}
			var now = clock.instant();
			if (problem != null) {
				intents.save(intent.with(UploadStatus.REJECTED, stored == null ? null : stored.size(), stored == null ? null : stored.contentType(), now));
				audit.record(AuditEvent.of("UPLOAD_REJECTED", "upload", intent.id(), actor, Map.of(), Map.of("reason", problem), problem));
				return new Verdict(intent, problem);
			}
			var product = products.findById(intent.productId()).orElseThrow(() -> new NotFoundException("Product", intent.productId()));
			var image = new ProductImage(UUID.randomUUID(), storage.publicUrl(intent.objectKey()), LocalizedText.empty(), product.images().size());
			products.save(product.withImage(image, now));
			var verified = intents.save(intent.with(UploadStatus.VERIFIED, stored.size(), stored.contentType(), now));
			audit.record(AuditEvent.of("PRODUCT_IMAGE_ADDED", "product", intent.productId(), actor, Map.of(), Map.of("objectKey", intent.objectKey(), "size", stored.size()), null));
			return new Verdict(verified, null);
		});
		if (verdict.problem() != null) {
			storage.delete(pending.objectKey());   // best effort, idempotent, outside the transaction
			throw new DomainRuleViolation(verdict.problem(), "The uploaded file was rejected: " + verdict.problem());
		}
		return verdict.intent();
	}

	private record Verdict(UploadIntent intent, String problem) {
	}

	/** Size within the declared limit, stored type equal to the declared one, and the FILE SIGNATURE matches that type (the client's MIME claim is not trusted). */
	private String verify(UploadIntent intent, ObjectStorage.StoredObject o) {
		if (o.size() < 1 || o.size() > intent.maxBytes()) {
			return "upload_too_large";
		}
		if (o.contentType() == null || !o.contentType().equalsIgnoreCase(intent.declaredContentType())) {
			return "upload_type_mismatch";
		}
		if (!signatureMatches(intent.declaredContentType(), o.head())) {
			return "upload_signature_mismatch";
		}
		return null;
	}

	static boolean signatureMatches(String type, byte[] h) {
		if (h == null) {
			return false;
		}
		return switch (type) {
			case "image/jpeg" -> h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
			case "image/png" -> h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A;
			case "image/webp" -> h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
			default -> false;
		};
	}

	@Override
	public int expireAbandoned() {
		var cutoff = clock.instant().minus(abandonedAfter);
		int count = 0;
		for (var stale : intents.findStaleRequested(cutoff)) {
			storage.delete(stale.objectKey());
			intents.save(stale.with(UploadStatus.EXPIRED, null, null, clock.instant()));
			count++;
		}
		return count;
	}
}
