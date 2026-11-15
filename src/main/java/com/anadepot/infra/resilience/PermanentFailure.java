package com.anadepot.infra.resilience;

/** A failure that retrying cannot fix (validation, authorization, 4xx other than 429). Never retried. */
public class PermanentFailure extends RuntimeException {

	public PermanentFailure(String message, Throwable cause) {
		super(message, cause);
	}

	public PermanentFailure(String message) {
		super(message);
	}
}
