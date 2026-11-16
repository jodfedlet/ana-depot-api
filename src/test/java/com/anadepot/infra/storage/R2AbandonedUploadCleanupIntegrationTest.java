package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.infra.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * An upload that was started, whose file reached the bucket, but that nobody completed: the scheduled cleanup deletes the object and expires the intent.
 * Real R2ObjectStorage against MinIO (S3-COMPATIBLE, NOT REAL CLOUDFLARE R2), real PostgreSQL.
 */
@TestPropertySource(properties = {"ana.upload.url-ttl=PT1S", "ana.upload.abandoned-after=PT1S"})
class R2AbandonedUploadCleanupIntegrationTest extends AbstractIntegrationTest {

	static final String BUCKET = MinioSupport.newBucket("cleanup");

	@DynamicPropertySource
	static void r2(DynamicPropertyRegistry registry) {
		registry.add("ana.storage.r2.endpoint", MinioSupport::endpoint);
		registry.add("ana.storage.r2.bucket-name", () -> BUCKET);
		registry.add("ana.storage.r2.access-key-id", () -> MinioSupport.ACCESS_KEY);
		registry.add("ana.storage.r2.secret-access-key", () -> MinioSupport.SECRET_KEY);
		registry.add("ana.storage.r2.public-base-url", () -> MinioSupport.endpoint() + "/" + BUCKET);
	}

	@Autowired UploadService uploads;
	@Autowired ObjectStorage storage;
	@Autowired UploadCleanupScheduler scheduler;

	@Test
	void anAbandonedUploadsObjectIsDeletedAndTheUploadCanNoLongerBeCompleted() throws Exception {
		var product = stockedMattress(1);
		var ticket = uploads.requestProductImage(product.id(), "image/jpeg", 1500, staff, key());
		var put = HttpRequest.newBuilder(URI.create(ticket.upload().url())).PUT(HttpRequest.BodyPublishers.ofByteArray(R2StorageMinioIntegrationTest.jpeg(1500)));
		ticket.upload().headers().forEach(put::header);
		assertThat(HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
		assertThat(storage.inspect(ticket.intent().objectKey(), 4)).isPresent();

		Thread.sleep(3200);                       // url-ttl 1 s + abandoned-after 1 s
		scheduler.run();                          // exactly what the scheduled job does

		assertThat(storage.inspect(ticket.intent().objectKey(), 4)).as("the abandoned object was deleted from the bucket").isEmpty();
		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), staff))
				.isInstanceOf(DomainRuleViolation.class).extracting(e -> ((DomainRuleViolation) e).code()).isEqualTo("upload_not_pending");
		scheduler.run();                          // idempotent: a second run has nothing to do and does not fail
	}
}
