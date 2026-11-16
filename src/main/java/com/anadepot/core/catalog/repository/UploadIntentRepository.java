package com.anadepot.core.catalog.repository;

import com.anadepot.core.catalog.model.UploadIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadIntentRepository {

	UploadIntent save(UploadIntent intent);

	Optional<UploadIntent> findById(UUID id);

	/** Loads holding a row lock: two simultaneous completions of one upload are serialised. */
	Optional<UploadIntent> findByIdForUpdate(UUID id);

	/** REQUESTED intents whose expiry is before {@code cutoff}. */
	List<UploadIntent> findStaleRequested(Instant cutoff);
}
