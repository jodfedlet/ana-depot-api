package com.anadepot.core.shared;

/** Writes the event to the outbox in the current transaction. It never publishes anything itself. */
public interface DomainEventRecorder {

	void record(DomainEvent event);
}
