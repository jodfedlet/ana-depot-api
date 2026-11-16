package com.anadepot.infra.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for append-only rows whose id is assigned by the application (audit trail, ledger, payments, idempotency keys,
 * processed events, outbox, rate-limit windows...). Without this, Spring Data treats an entity with an assigned id as
 * "possibly existing" and calls {@code merge}: if a concurrent transaction has just committed the same key, merge would
 * SILENTLY OVERWRITE that row instead of failing on the unique constraint. Declaring the entity "new" makes
 * {@code save} use {@code persist}, so the database constraint stays the arbiter of duplicates.
 */
@MappedSuperclass
public abstract class InsertOnlyEntity<ID> implements Persistable<ID> {

	@Transient
	private boolean fresh = true;

	@Override
	public boolean isNew() {
		return fresh;
	}

	@PostPersist
	@PostLoad
	void markPersisted() {
		this.fresh = false;
	}
}
