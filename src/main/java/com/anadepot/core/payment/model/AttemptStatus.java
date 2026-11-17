package com.anadepot.core.payment.model;

/** State of one online payment attempt (one provider Checkout Session). SUCCEEDED and FAILED/EXPIRED are terminal; SUCCEEDED always wins (the money moved). */
public enum AttemptStatus {
	PENDING, PROCESSING, SUCCEEDED, FAILED, EXPIRED;

	public boolean open() {
		return this == PENDING || this == PROCESSING;
	}
}
