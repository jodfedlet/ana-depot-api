package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.shared.DomainRuleViolation;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Presigning is a LOCAL computation (no network): what the URL looks like for R2's endpoint, region "auto" and the signed headers. These do not talk to R2 or to any
 * S3 server; see R2StorageMinioIntegrationTest (S3-compatible, NOT real R2) and R2RealIntegrationTest (opt-in, real R2).
 */
class R2PresignTest {

	private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
	private static final String ENDPOINT = "https://" + ACCOUNT + ".r2.cloudflarestorage.com";
	private static final String ACCESS_KEY = "test-access-key-id-0001";
	private static final String SECRET = "test-secret-access-key-that-must-never-appear-anywhere-0123456789";

	private static R2ObjectStorage storage(boolean pathStyle) {
		return new R2ObjectStorage(ENDPOINT, "auto", "ana-depot-images", ACCESS_KEY, SECRET, "https://images.example.test/", pathStyle);
	}

	private static Map<String, String> query(URI uri) {
		return Arrays.stream(uri.getRawQuery().split("&")).map(p -> p.split("=", 2))
				.collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8)));
	}

	@Test
	void pathStyleUrlUsesTheAccountEndpointTheBucketAndTheKeyWithRegionAuto() {
		var presigned = storage(true).presignPut("product-images/p1/obj.jpg", "image/jpeg", 12345, Duration.ofMinutes(10));
		var uri = URI.create(presigned.url());

		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getHost()).isEqualTo(ACCOUNT + ".r2.cloudflarestorage.com");
		assertThat(uri.getRawPath()).isEqualTo("/ana-depot-images/product-images/p1/obj.jpg");
		assertThat(presigned.method()).isEqualTo("PUT");
		var q = query(uri);
		assertThat(q.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
		assertThat(q.get("X-Amz-Credential")).startsWith(ACCESS_KEY + "/").endsWith("/auto/s3/aws4_request");     // region "auto", service s3
	}

	@Test
	void virtualHostedStyleMovesTheBucketIntoTheHost() {
		var uri = URI.create(storage(false).presignPut("product-images/p1/obj.jpg", "image/jpeg", 10, Duration.ofMinutes(5)).url());
		assertThat(uri.getHost()).isEqualTo("ana-depot-images." + ACCOUNT + ".r2.cloudflarestorage.com");
		assertThat(uri.getRawPath()).isEqualTo("/product-images/p1/obj.jpg");
	}

	@Test
	void theExpiryIsExactlyTheRequestedTtl() {
		var before = Instant.now();
		var presigned = storage(true).presignPut("k.jpg", "image/jpeg", 10, Duration.ofSeconds(900));
		assertThat(query(URI.create(presigned.url())).get("X-Amz-Expires")).isEqualTo("900");
		assertThat(presigned.expiresAt()).isBetween(before.plusSeconds(895), Instant.now().plusSeconds(905));
	}

	@Test
	void contentTypeAndContentLengthAreSigned_andTheClientIsToldTheContentType() {
		var presigned = storage(true).presignPut("k.png", "image/png", 4321, Duration.ofMinutes(10));
		var signed = query(URI.create(presigned.url())).get("X-Amz-SignedHeaders");
		assertThat(signed.split(";")).contains("host", "content-type", "content-length");
		assertThat(presigned.headers()).containsEntry("Content-Type", "image/png").doesNotContainKeys("Host", "host", "Content-Length", "content-length");
	}

	@Test
	void noChecksumRequirementIsAddedThatABrowserUploadCouldNotMeet() {
		var uri = URI.create(storage(true).presignPut("k.jpg", "image/jpeg", 10, Duration.ofMinutes(10)).url());
		assertThat(uri.getRawQuery().toLowerCase()).doesNotContain("checksum");
		assertThat(query(uri).get("X-Amz-SignedHeaders")).doesNotContain("x-amz-checksum").doesNotContain("x-amz-sdk-checksum");
	}

	@Test
	void theSecretAccessKeyNeverAppearsInTheUrlTheHeadersOrAnyToString() {
		var storage = storage(true);
		var presigned = storage.presignPut("k.jpg", "image/jpeg", 10, Duration.ofMinutes(10));
		assertThat(presigned.url()).doesNotContain(SECRET).doesNotContain("secret");
		assertThat(presigned.headers().toString()).doesNotContain(SECRET);
		assertThat(presigned.toString()).doesNotContain(SECRET);
		assertThat(storage.toString()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY);
		var properties = new com.anadepot.infra.config.AnaProperties.R2(ACCOUNT, "b", ACCESS_KEY, SECRET, null, "https://images.example.test", true);
		assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY);
	}

	@Test
	void theSignatureDependsOnTheContentTypeAndTheSize() {
		var storage = storage(true);
		var base = query(URI.create(storage.presignPut("k.jpg", "image/jpeg", 100, Duration.ofSeconds(600)).url())).get("X-Amz-Signature");
		var otherType = query(URI.create(storage.presignPut("k.jpg", "image/png", 100, Duration.ofSeconds(600)).url())).get("X-Amz-Signature");
		var otherSize = query(URI.create(storage.presignPut("k.jpg", "image/jpeg", 101, Duration.ofSeconds(600)).url())).get("X-Amz-Signature");
		assertThat(base).isNotEqualTo(otherType).isNotEqualTo(otherSize);
	}

	@Test
	void aTtlOutsideWhatR2AllowsIsRefused() {
		var storage = storage(true);
		assertThatThrownBy(() -> storage.presignPut("k.jpg", "image/jpeg", 10, Duration.ofDays(8))).isInstanceOf(DomainRuleViolation.class);
		assertThatThrownBy(() -> storage.presignPut("k.jpg", "image/jpeg", 10, Duration.ZERO)).isInstanceOf(DomainRuleViolation.class);
		assertThat(storage.presignPut("k.jpg", "image/jpeg", 10, Duration.ofDays(7)).url()).contains("X-Amz-Expires=604800");
	}

	@Test
	void publicUrlsUseTheConfiguredBaseWithoutDoubleSlashes() {
		assertThat(storage(true).publicUrl("product-images/p/o.jpg")).isEqualTo("https://images.example.test/product-images/p/o.jpg");
	}
}
