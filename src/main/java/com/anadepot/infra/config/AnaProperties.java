package com.anadepot.infra.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Every operational limit is configuration (ana.*), never a constant buried in code. */
@ConfigurationProperties(prefix = "ana")
public record AnaProperties(
		@DefaultValue Idempotency idempotency,
		@DefaultValue Outbox outbox,
		@DefaultValue RateLimit rateLimit,
		@DefaultValue Security security,
		@DefaultValue Upload upload,
		@DefaultValue Storage storage,
		@DefaultValue Legal legal) {

	/**
	 * {@code termsVersion}: label of the reservation terms currently in force (for example a date or a number the business assigns). The legal text is supplied
	 * separately and is NOT part of this application. Optional: when empty, reservations still record WHEN the terms were accepted but no version.
	 */
	public record Legal(String termsVersion) {
	}


	public record Idempotency(@DefaultValue("P7D") Duration retention, @DefaultValue("PT1H") Duration cleanupInterval) {
	}

	public record Outbox(@DefaultValue("true") boolean dispatcherEnabled, @DefaultValue("50") int batchSize,
			@DefaultValue("PT2S") Duration pollInterval, @DefaultValue("PT1M") Duration leaseDuration,
			@DefaultValue("8") int maxAttempts, @DefaultValue("PT5S") Duration initialBackoff,
			@DefaultValue("2.0") double backoffMultiplier, @DefaultValue("PT10M") Duration maxBackoff,
			@DefaultValue("P14D") Duration processedRetention) {
	}

	/** {@code limits}: operation name -> limit. Unknown operations fall back to {@code defaultLimit}. */
	public record RateLimit(@DefaultValue("true") boolean enabled, List<String> trustedProxies,
			@DefaultValue Limit defaultLimit, Map<String, Limit> limits, @DefaultValue("PT1H") Duration cleanupRetention) {
	}

	public record Limit(@DefaultValue("120") int maxRequests, @DefaultValue("PT1M") Duration window) {
	}

	/**
	 * What a Supabase access token must carry. {@code jwtIssuer}: {@code https://<project-ref>.supabase.co/auth/v1}; when empty it is derived from
	 * {@code supabaseUrl} (the project URL) + {@code /auth/v1}; when both are empty every token is rejected. {@code jwksUri}: optional override of
	 * the key-set URL (default {@code <issuer>/.well-known/jwks.json}). {@code bootstrapAdminSubject}: see docs/auth.md.
	 */
	public record Security(String supabaseUrl, String jwtIssuer, String jwksUri, @DefaultValue("authenticated") String jwtAudience, String bootstrapAdminSubject) {

		public String effectiveIssuer() {
			if (jwtIssuer != null && !jwtIssuer.isBlank()) {
				return jwtIssuer.strip();
			}
			return supabaseUrl == null || supabaseUrl.isBlank() ? null : supabaseUrl.strip().replaceAll("/+$", "") + "/auth/v1";
		}
	}

	/**
	 * Image upload policy. {@code urlTtl} is how long the presigned PUT URL stays valid (environment {@code R2_PRESIGNED_URL_EXPIRATION_SECONDS}; Cloudflare R2 allows 1 s to 7 days),
	 * {@code maxBytes} the largest accepted image ({@code R2_MAX_UPLOAD_SIZE_BYTES}). {@code cleanupInterval}: how often abandoned uploads are expired and their objects deleted.
	 */
	public record Upload(@DefaultValue("PT10M") Duration urlTtl, @DefaultValue("5242880") long maxBytes,
			@DefaultValue({"image/jpeg", "image/png", "image/webp"}) List<String> allowedContentTypes,
			@DefaultValue("PT1H") Duration abandonedAfter, @DefaultValue("PT15M") Duration cleanupInterval) {
	}

	public record Storage(@DefaultValue R2 r2) {
	}

	/**
	 * Cloudflare R2 (S3-compatible API). Nothing set = object storage is not configured and every storage call fails closed. Only a COMPLETE set creates the
	 * adapter. {@code endpoint} defaults to {@code https://<accountId>.r2.cloudflarestorage.com}. {@code publicBaseUrl}: the custom domain (or r2.dev during
	 * development) the bucket is publicly readable from; image URLs are built from it. {@code toString} never prints the secret.
	 */
	public record R2(String accountId, String bucketName, String accessKeyId, String secretAccessKey, String endpoint, String publicBaseUrl,
			@DefaultValue("true") boolean pathStyleAccess) {

		private static boolean blank(String s) {
			return s == null || s.isBlank();
		}

		/** Anything set at all? (distinguishes "not used" from "half configured") */
		public boolean anySet() {
			return !blank(accountId) || !blank(bucketName) || !blank(accessKeyId) || !blank(secretAccessKey) || !blank(endpoint) || !blank(publicBaseUrl);
		}

		public String effectiveEndpoint() {
			if (!blank(endpoint)) {
				return endpoint.strip().replaceAll("/+$", "");
			}
			return blank(accountId) ? null : "https://" + accountId.strip() + ".r2.cloudflarestorage.com";
		}

		/** Names (environment variables) of what is missing; empty when complete. */
		public List<String> missing() {
			var missing = new java.util.ArrayList<String>();
			if (effectiveEndpoint() == null) {
				missing.add("R2_ACCOUNT_ID (or R2_ENDPOINT)");
			}
			if (blank(bucketName)) {
				missing.add("R2_BUCKET_NAME");
			}
			if (blank(accessKeyId)) {
				missing.add("R2_ACCESS_KEY_ID");
			}
			if (blank(secretAccessKey)) {
				missing.add("R2_SECRET_ACCESS_KEY");
			}
			if (blank(publicBaseUrl)) {
				missing.add("R2_PUBLIC_BASE_URL");
			}
			return missing;
		}

		public boolean complete() {
			return missing().isEmpty();
		}

		@Override
		public String toString() {
			return "R2[bucketName=" + bucketName + ", endpoint=" + effectiveEndpoint() + ", accessKeyId=" + (blank(accessKeyId) ? "unset" : "***") + ", secretAccessKey="
					+ (blank(secretAccessKey) ? "unset" : "***") + ", publicBaseUrl=" + publicBaseUrl + "]";
		}
	}
}
