package com.anadepot.core.staff.model;

import java.time.Instant;
import java.util.List;

/** The result of generating codes (shown ONCE) and the summary shown afterwards (never the codes). */
public final class RecoveryCodes {

	private RecoveryCodes() {
	}

	/** {@code codes} are the only copy in clear text: the caller shows them once and drops them. */
	public record Issued(List<String> codes, Instant generatedAt) {

		@Override
		public String toString() {
			return "RecoveryCodes.Issued[codes=hidden]";
		}
	}

	public record Status(int remaining, Instant generatedAt) {
	}

	/** What redeeming a code granted: this session may use the administration until {@code expiresAt}. */
	public record Grant(Instant expiresAt, int remaining) {
	}
}
