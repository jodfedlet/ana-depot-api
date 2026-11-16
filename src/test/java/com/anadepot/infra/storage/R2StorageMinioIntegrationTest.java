package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.DomainRuleViolation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link R2ObjectStorage} against a REAL S3-compatible server (MinIO in Testcontainers), through REAL HTTP PUTs of the presigned URL, exactly as a browser would.
 * S3-COMPATIBLE (MinIO), NOT REAL CLOUDFLARE R2: see MinioSupport and R2RealIntegrationTest.
 */
class R2StorageMinioIntegrationTest {

	static final byte[] JPEG = jpeg(2048);
	static final byte[] PNG = png(1024);
	static R2ObjectStorage storage;
	static final HttpClient HTTP = HttpClient.newHttpClient();

	@BeforeAll
	static void bucket() {
		storage = MinioSupport.storage(MinioSupport.newBucket("adapter"));
	}

	static byte[] jpeg(int size) {
		var b = new byte[size];
		b[0] = (byte) 0xFF;
		b[1] = (byte) 0xD8;
		b[2] = (byte) 0xFF;
		b[3] = (byte) 0xE0;
		return b;
	}

	static byte[] png(int size) {
		var b = new byte[size];
		System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, b, 0, 8);
		return b;
	}

	static String key() {
		return "product-images/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg";
	}

	static HttpResponse<String> put(String url, String contentType, byte[] body) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void aValidJpegUploadedWithThePresignedUrlIsStoredAndInspectable() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));

		var response = put(ticket.url(), ticket.headers().get("Content-Type"), JPEG);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

		var stored = storage.inspect(key, 16).orElseThrow();
		assertThat(stored.size()).isEqualTo(JPEG.length);
		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.head()).hasSize(16).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
	}

	@Test
	void aWrongContentTypeIsRefusedByTheSignature() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		var response = put(ticket.url(), "text/html", JPEG);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(storage.inspect(key, 16)).isEmpty();
	}

	@Test
	void aMissingContentTypeIsRefused() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), null, JPEG).statusCode()).isIn(400, 403);
		assertThat(storage.inspect(key, 16)).isEmpty();
	}

	@Test
	void aBodyLargerOrSmallerThanTheSignedSizeIsRefused() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "image/jpeg", jpeg(JPEG.length * 4)).statusCode()).as("oversize").isGreaterThanOrEqualTo(400);
		assertThat(put(ticket.url(), "image/jpeg", jpeg(JPEG.length / 2)).statusCode()).as("undersize").isGreaterThanOrEqualTo(400);
		assertThat(storage.inspect(key, 16)).isEmpty();
	}

	@Test
	void anExpiredUrlIsRefused() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofSeconds(1));
		Thread.sleep(2500);
		assertThat(put(ticket.url(), "image/jpeg", JPEG).statusCode()).isEqualTo(403);
		assertThat(storage.inspect(key, 16)).isEmpty();
	}

	@Test
	void aTamperedObjectKeyInvalidatesTheSignature() throws Exception {
		var ticket = storage.presignPut(key(), "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		var otherKey = key();
		var tampered = ticket.url().replaceAll("product-images/[^?]*", otherKey);
		assertThat(tampered).isNotEqualTo(ticket.url());
		assertThat(put(tampered, "image/jpeg", JPEG).statusCode()).isEqualTo(403);
		assertThat(storage.inspect(otherKey, 16)).isEmpty();
	}

	@Test
	void theUrlCannotBeUsedToReplaceAnotherImagesObjectBecauseEveryTicketHasItsOwnKey() throws Exception {
		var firstKey = key();
		var first = storage.presignPut(firstKey, "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		assertThat(put(first.url(), "image/jpeg", JPEG).statusCode()).isEqualTo(200);
		var second = storage.presignPut(key(), "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		assertThat(second.url()).doesNotContain(firstKey);
		assertThat(put(second.url(), "image/jpeg", jpeg(JPEG.length)).statusCode()).isEqualTo(200);
		assertThat(storage.inspect(firstKey, 4).orElseThrow().size()).isEqualTo(JPEG.length);
	}

	@Test
	void aPngDeclaredAsJpegIsStoredButItsRealBytesAreVisibleToTheVerification() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", PNG.length, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "image/jpeg", PNG).statusCode()).as("the signature cannot know what the bytes are").isEqualTo(200);
		var stored = storage.inspect(key, 16).orElseThrow();
		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.head()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');   // UploadService rejects this: signature != declared type
	}

	@Test
	void aMissingObjectIsEmptyAndDeletingIsIdempotent() {
		var key = key();
		assertThat(storage.inspect(key, 16)).isEmpty();
		storage.delete(key);                     // deleting what is not there is not an error
		storage.delete(key);
	}

	@Test
	void deletingRemovesTheObject() throws Exception {
		var key = key();
		var ticket = storage.presignPut(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));
		assertThat(put(ticket.url(), "image/jpeg", JPEG).statusCode()).isEqualTo(200);
		assertThat(storage.inspect(key, 4)).isPresent();
		storage.delete(key);
		assertThat(storage.inspect(key, 4)).isEmpty();
	}

	@Test
	void anUnreachableStoreIsStorageUnavailableNotAStackTrace() {
		try (var down = new R2ObjectStorage("http://127.0.0.1:1", "auto", "b", "k", "s", "http://127.0.0.1:1/b", true)) {
			assertThatThrownBy(() -> down.inspect("x.jpg", 4)).isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("storage_unavailable");
			assertThatThrownBy(() -> down.delete("x.jpg")).isInstanceOf(DomainRuleViolation.class).hasMessageNotContaining("127.0.0.1");
		}
	}

	@Test
	void wrongCredentialsAreStorageUnavailableAndTheSecretIsNeverInTheMessage() {
		try (var wrong = new R2ObjectStorage(MinioSupport.endpoint(), "auto", "whatever", "wrong-access", "wrong-secret-value-123", MinioSupport.endpoint() + "/whatever", true)) {
			assertThatThrownBy(() -> wrong.inspect("x.jpg", 4)).isInstanceOf(DomainRuleViolation.class).hasMessageNotContaining("wrong-secret-value-123").hasMessageNotContaining("wrong-access");
		}
	}

	@SuppressWarnings("unused")
	private static ObjectStorage asPort() {
		return storage;
	}
}
