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
		@DefaultValue Upload upload) {

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

	/** {@code jwtIssuer}/{@code jwtAudience}: what a Supabase access token must carry. {@code bootstrapAdminSubject}: see docs/auth.md. */
	public record Security(String jwtIssuer, @DefaultValue("authenticated") String jwtAudience, String bootstrapAdminSubject) {
	}

	public record Upload(@DefaultValue("PT10M") Duration urlTtl, @DefaultValue("5242880") long maxBytes,
			@DefaultValue({"image/jpeg", "image/png", "image/webp"}) List<String> allowedContentTypes,
			@DefaultValue("PT1H") Duration abandonedAfter) {
	}
}
