package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * OPT-IN: talks to the REAL Cloudflare R2 bucket named by the environment. Skipped unless {@code R2_TEST_ENABLED=true}. It writes objects under a throwaway prefix
 * ({@code ana-depot-integration-test/<uuid>/}) and deletes them afterwards. Use a bucket and token reserved for tests, never the production bucket.
 *
 * <pre>
 * R2_TEST_ENABLED=true R2_ACCOUNT_ID=... R2_BUCKET_NAME=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... [R2_PUBLIC_BASE_URL=https://images.example.com] \
 *   mvn test -Dtest=R2RealIntegrationTest
 * </pre>
 * The results of a run are what docs/storage-r2.md calls "verified against real R2". Without a run they are PENDING.
 */
@EnabledIfEnvironmentVariable(named = "R2_TEST_ENABLED", matches = "true")
class R2RealIntegrationTest {

	static final HttpClient HTTP = HttpClient.newHttpClient();
	static R2ObjectStorage storage;
	static final String PREFIX = "ana-depot-integration-test/" + UUID.randomUUID() + "/";
	static final List<String> created = new ArrayList<>();
	static String publicBase;

	@BeforeAll
	static void connect() {
		var account = require("R2_ACCOUNT_ID");
		publicBase = System.getenv().getOrDefault("R2_PUBLIC_BASE_URL", "");
		var endpoint = System.getenv().getOrDefault("R2_ENDPOINT", "https://" + account + ".r2.cloudflarestorage.com");
		storage = new R2ObjectStorage(endpoint, "auto", require("R2_BUCKET_NAME"), require("R2_ACCESS_KEY_ID"), require("R2_SECRET_ACCESS_KEY"),
				publicBase.isBlank() ? "https://unused.invalid" : publicBase, true);
	}

	@AfterAll
	static void cleanUp() {
		if (storage != null) {
			created.forEach(storage::delete);
			storage.close();
		}
	}

	private static String require(String name) {
		var value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set when R2_TEST_ENABLED=true");
		}
		return value;
	}

	private static String key() {
		var key = PREFIX + UUID.randomUUID() + ".jpg";
		created.add(key);
		return key;
	}

	private static int put(String url, String contentType, byte[] body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	@Test
	void validUploadIsStoredAndInspectable() throws Exception {
		var key = key();
		var jpeg = R2StorageMinioIntegrationTest.jpeg(4096);
		var ticket = storage.presignPut(key, "image/jpeg", jpeg.length, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "image/jpeg", jpeg)).isEqualTo(200);
		var stored = storage.inspect(key, 16).orElseThrow();
		assertThat(stored.size()).isEqualTo(jpeg.length);
		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.head()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
	}

	@Test
	void wrongContentTypeIsRefused() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", 1024, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "text/html", R2StorageMinioIntegrationTest.jpeg(1024))).isGreaterThanOrEqualTo(400);
		assertThat(storage.inspect(key, 4)).isEmpty();
	}

	/** Records whether R2 enforces the SIGNED Content-Length. If this fails, rely on the post-upload size check only and say so in docs/storage-r2.md. */
	@Test
	void oversizedBodyIsRefusedBecauseContentLengthIsSigned() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", 1024, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "image/jpeg", R2StorageMinioIntegrationTest.jpeg(8192))).isGreaterThanOrEqualTo(400);
		assertThat(storage.inspect(key, 4)).isEmpty();
	}

	@Test
	void expiredUrlIsRefused() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", 1024, Duration.ofSeconds(1));
		Thread.sleep(3000);
		assertThat(put(ticket.url(), "image/jpeg", R2StorageMinioIntegrationTest.jpeg(1024))).isGreaterThanOrEqualTo(400);
	}

	@Test
	void missingObjectAndIdempotentDelete() {
		var key = key();
		assertThat(storage.inspect(key, 4)).isEmpty();
		storage.delete(key);
	}

	@Test
	void publicDomainServesTheObjectWhenConfigured() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeFalse(publicBase.isBlank(), "R2_PUBLIC_BASE_URL not set");
		var key = key();
		var jpeg = R2StorageMinioIntegrationTest.jpeg(2048);
		assertThat(put(storage.presignPut(key, "image/jpeg", jpeg.length, Duration.ofMinutes(5)).url(), "image/jpeg", jpeg)).isEqualTo(200);
		var response = HTTP.send(HttpRequest.newBuilder(URI.create(storage.publicUrl(key))).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type")).hasValue("image/jpeg");
		assertThat(response.body()).hasSize(jpeg.length);
	}
}
