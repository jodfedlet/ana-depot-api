package com.anadepot.core.catalog.service;

import com.anadepot.core.catalog.model.UploadIntent;
import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.Actor;
import java.util.UUID;

/**
 * Product image upload lifecycle: request (validate, create intent, presign) -> client PUTs to storage -> complete
 * (verify the stored object, attach it to the product) -> abandoned intents are cleaned up. Which staff may do this is an
 * authorization concern decided at the API boundary; this service only requires an identified actor.
 */
public interface UploadService {

	record Ticket(UploadIntent intent, ObjectStorage.PresignedUpload upload) {
	}

	Ticket requestProductImage(UUID productId, String contentType, long declaredSize, Actor actor, String idempotencyKey);

	/** Verifies size, content type and file signature of what was ACTUALLY uploaded, then attaches it. Repeatable. */
	UploadIntent completeProductImage(UUID intentId, Actor actor);

	/** Same, but only if the upload was requested for THIS product; an upload of another product looks exactly like a missing one and nothing is attached. */
	UploadIntent completeProductImage(UUID productId, UUID intentId, Actor actor);

	/** Marks stale unfinished intents EXPIRED and deletes their objects. Idempotent and safe to run on every instance. @return number expired. */
	int expireAbandoned();
}
