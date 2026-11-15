package com.anadepot.core.shared;

/** Identifier tying together everything done on behalf of one request (logs, audit events, outbox events). */
public interface CorrelationContext {

	/** The current correlation id, or null outside a request. */
	String correlationId();
}
