package com.anadepot.core.shared;

/** Appends to the immutable audit trail, in the CURRENT transaction (the change and its audit record commit together). */
public interface AuditRecorder {

	void record(AuditEvent event);
}
