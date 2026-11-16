package com.anadepot.infra.storage;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.Product;
import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.UploadStatus;
import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.NotFoundException;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import com.anadepot.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Upload authorization, key control, expiry, content verification and attachment, against a controllable in-memory store. */
@Import(UploadLifecycleIntegrationTest.FakeStorageConfig.class)
class UploadLifecycleIntegrationTest extends AbstractIntegrationTest {

	/** In-memory object store for tests. Real adapters (Supabase Storage / S3) live in infra when a provider is chosen. */
	static class FakeObjectStorage implements ObjectStorage {
		final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
		final Map<String, PresignedUpload> presigned = new ConcurrentHashMap<>();
		int deletes;

		@Override
		public PresignedUpload presignPut(String objectKey, String contentType, long maxBytes, Duration ttl) {
			var p = new PresignedUpload("https://storage.test/put/" + objectKey, "PUT", Map.of("Content-Type", contentType), Instant.now().plus(ttl));
			presigned.put(objectKey, p);
			return p;
		}

		@Override
		public Optional<StoredObject> inspect(String objectKey, int headBytes) {
			return Optional.ofNullable(objects.get(objectKey));
		}

		@Override
		public void delete(String objectKey) {
			objects.remove(objectKey);
			deletes++;
		}

		@Override
		public String publicUrl(String objectKey) {
			return "https://cdn.test/" + objectKey;
		}

		/** What the browser does with the presigned URL. */
		void clientUploads(String key, String contentType, byte[] bytes, long reportedSize) {
			objects.put(key, new StoredObject(reportedSize, contentType, java.util.Arrays.copyOf(bytes, Math.min(16, bytes.length))));
		}
	}

	@TestConfiguration
	static class FakeStorageConfig {
		@Bean
		@Primary
		FakeObjectStorage fakeObjectStorage() {
			return new FakeObjectStorage();
		}
	}

	static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1};
	static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

	@Autowired UploadService uploads;
	@Autowired FakeObjectStorage storage;
	@Autowired UploadIntentJpaRepository intentJpa;
	@Autowired AuditEventJpaRepository audit;

	private Product aProduct() {
		var category = TestData.category().category();
		catalog.saveCategory(category);
		return catalog.saveProduct(product(category.id(), ProductStatus.ACTIVE, price(htg("10.00"), null, 1)), staff);
	}

	// ---------------------------------------------------------------- request

	@Test
	void theBackendChoosesTheObjectKey_andThePresignedUrlIsShortLived() {
		var p = aProduct();
		var one = uploads.requestProductImage(p.id(), "image/jpeg", 1000, staff, key());
		var two = uploads.requestProductImage(p.id(), "image/jpeg", 1000, staff, key());

		assertThat(one.intent().objectKey()).matches("product-images/" + p.id() + "/[0-9a-f\\-]{36}\\.jpg");
		assertThat(two.intent().objectKey()).isNotEqualTo(one.intent().objectKey());       // never reused, so nothing is overwritten
		assertThat(one.upload().url()).endsWith(one.intent().objectKey());
		assertThat(one.upload().method()).isEqualTo("PUT");
		assertThat(one.upload().expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(9)), Instant.now().plus(Duration.ofMinutes(11)));
		assertThat(one.intent().expiresAt()).isEqualTo(one.intent().createdAt().plus(Duration.ofMinutes(10)));
		assertThat(one.intent().status()).isEqualTo(UploadStatus.REQUESTED);
	}

	@Test
	void typeSizeAuthenticationAndProductAreValidatedBeforeAnythingIsPresigned() {
		var p = aProduct();
		assertThatThrownBy(() -> uploads.requestProductImage(p.id(), "text/html", 100, staff, key())).extracting("code").isEqualTo("upload_type_not_allowed");
		assertThatThrownBy(() -> uploads.requestProductImage(p.id(), "image/svg+xml", 100, staff, key())).extracting("code").isEqualTo("upload_type_not_allowed");
		assertThatThrownBy(() -> uploads.requestProductImage(p.id(), "image/jpeg", 0, staff, key())).extracting("code").isEqualTo("upload_too_large");
		assertThatThrownBy(() -> uploads.requestProductImage(p.id(), "image/jpeg", 5_242_881, staff, key())).extracting("code").isEqualTo("upload_too_large");
		assertThatThrownBy(() -> uploads.requestProductImage(p.id(), "image/jpeg", 100, Actor.guest(), key())).extracting("code").isEqualTo("authentication_required");
		assertThatThrownBy(() -> uploads.requestProductImage(UUID.randomUUID(), "image/jpeg", 100, staff, key())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void aRetriedRequestWithTheSameKeyReusesTheIntent() {
		var p = aProduct();
		var key = key();
		var first = uploads.requestProductImage(p.id(), "image/png", 2000, staff, key);
		var retry = uploads.requestProductImage(p.id(), "image/png", 2000, staff, key);
		assertThat(retry.intent().id()).isEqualTo(first.intent().id());
		assertThat(retry.intent().objectKey()).isEqualTo(first.intent().objectKey());
	}

	// ---------------------------------------------------------------- completion

	@Test
	void aVerifiedUploadIsAttachedToTheProductAndCompletionIsRepeatable() {
		var p = aProduct();
		var ticket = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(ticket.intent().objectKey(), "image/jpeg", JPEG, 100);

		var done = uploads.completeProductImage(ticket.intent().id(), staff);

		assertThat(done.status()).isEqualTo(UploadStatus.VERIFIED);
		assertThat(done.verifiedSize()).isEqualTo(100);
		var images = catalog.getProduct(p.id()).images();
		assertThat(images).hasSize(1);
		assertThat(images.getFirst().url()).isEqualTo("https://cdn.test/" + ticket.intent().objectKey());
		assertThat(images.getFirst().position()).isZero();
		// repeating is harmless: no second image
		assertThat(uploads.completeProductImage(ticket.intent().id(), staff).status()).isEqualTo(UploadStatus.VERIFIED);
		assertThat(catalog.getProduct(p.id()).images()).hasSize(1);
		assertThat(audit.findByTargetTypeAndTargetIdOrderByOccurredAtAsc("product", p.id().toString())).extracting(e -> e.getAction()).contains("PRODUCT_IMAGE_ADDED");
	}

	@Test
	void theClientsClaimedTypeIsNotTrusted_theFileSignatureMustMatch() {
		var p = aProduct();
		var ticket = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(ticket.intent().objectKey(), "image/jpeg", PNG, 100);   // claims JPEG, bytes are PNG

		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), staff)).isInstanceOf(DomainRuleViolation.class)
				.extracting("code").isEqualTo("upload_signature_mismatch");

		assertThat(intentJpa.findById(ticket.intent().id()).orElseThrow().status).isEqualTo(UploadStatus.REJECTED);   // the rejection was committed
		assertThat(storage.objects).doesNotContainKey(ticket.intent().objectKey());                                 // the bad object was deleted
		assertThat(catalog.getProduct(p.id()).images()).isEmpty();
		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), staff)).extracting("code").isEqualTo("upload_not_pending");
	}

	@Test
	void contentTypeSizeAndExistenceAreVerifiedAgainstWhatWasReallyStored() {
		var p = aProduct();
		var wrongType = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(wrongType.intent().objectKey(), "image/png", JPEG, 100);
		assertThatThrownBy(() -> uploads.completeProductImage(wrongType.intent().id(), staff)).extracting("code").isEqualTo("upload_type_mismatch");

		var tooBig = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(tooBig.intent().objectKey(), "image/jpeg", JPEG, 9_999_999);   // declared 100 bytes, actually huge
		assertThatThrownBy(() -> uploads.completeProductImage(tooBig.intent().id(), staff)).extracting("code").isEqualTo("upload_too_large");

		var never = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());   // the client never uploaded anything
		assertThatThrownBy(() -> uploads.completeProductImage(never.intent().id(), staff)).extracting("code").isEqualTo("upload_missing");
		assertThat(catalog.getProduct(p.id()).images()).isEmpty();
	}

	@Test
	void onlyTheRequesterCanCompleteAnUpload_othersGetNotFound() {
		var p = aProduct();
		var ticket = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(ticket.intent().objectKey(), "image/jpeg", JPEG, 100);
		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), Actor.staff(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), Actor.guest())).isInstanceOf(NotFoundException.class);
		assertThat(catalog.getProduct(p.id()).images()).isEmpty();
	}

	@Test
	void simultaneousCompletionsAttachTheImageOnce() throws Exception {
		var p = aProduct();
		var ticket = uploads.requestProductImage(p.id(), "image/jpeg", 100, staff, key());
		storage.clientUploads(ticket.intent().objectKey(), "image/jpeg", JPEG, 100);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
		var futures = new java.util.ArrayList<java.util.concurrent.Future<UploadStatus>>();
		for (int i = 0; i < 6; i++) {
			futures.add(pool.submit(() -> uploads.completeProductImage(ticket.intent().id(), staff).status()));
		}
		for (var f : futures) {
			assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(UploadStatus.VERIFIED);
		}
		pool.shutdown();
		assertThat(catalog.getProduct(p.id()).images()).hasSize(1);
	}
}
