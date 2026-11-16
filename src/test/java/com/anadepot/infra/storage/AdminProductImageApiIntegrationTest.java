package com.anadepot.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anadepot.infra.ApiTestSupport;
import com.anadepot.infra.outbox.OutboxDispatcher;
import com.anadepot.infra.outbox.OutboxEventHandler;
import com.anadepot.infra.outbox.OutboxMessage;
import com.anadepot.infra.persistence.AuditEventJpaRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The staff image endpoints end to end: real security chain, real use cases and PostgreSQL, the REAL {@link R2ObjectStorage} adapter selected by configuration,
 * and a REAL S3-compatible server (MinIO in Testcontainers) reached by real HTTP PUTs. S3-COMPATIBLE (MinIO), NOT REAL CLOUDFLARE R2.
 */
@Import(AdminProductImageApiIntegrationTest.Capture.class)
class AdminProductImageApiIntegrationTest extends ApiTestSupport {

	static final String BUCKET = MinioSupport.newBucket("images");
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final HttpClient HTTP = HttpClient.newHttpClient();

	@DynamicPropertySource
	static void r2(DynamicPropertyRegistry registry) {
		registry.add("ana.storage.r2.endpoint", MinioSupport::endpoint);
		registry.add("ana.storage.r2.bucket-name", () -> BUCKET);
		registry.add("ana.storage.r2.access-key-id", () -> MinioSupport.ACCESS_KEY);
		registry.add("ana.storage.r2.secret-access-key", () -> MinioSupport.SECRET_KEY);
		registry.add("ana.storage.r2.public-base-url", () -> MinioSupport.endpoint() + "/" + BUCKET);
		registry.add("ana.upload.max-bytes", () -> "100000");
	}

	static class Capturing implements OutboxEventHandler {
		final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

		@Override
		public String name() {
			return "image-test-capture";
		}

		@Override
		public boolean supports(String eventType) {
			return eventType.startsWith("product.image.");
		}

		@Override
		public void handle(OutboxMessage message) {
			messages.add(message);
		}
	}

	@TestConfiguration
	static class Capture {
		@Bean
		Capturing capturingImageEvents() {
			return new Capturing();
		}
	}

	@Autowired com.anadepot.core.catalog.repository.ObjectStorage storage;
	@Autowired Capturing capture;
	@Autowired OutboxDispatcher dispatcher;
	@Autowired AuditEventJpaRepository audits;

	private com.anadepot.core.catalog.repository.ObjectStorage objects() {
		return storage;
	}

	static byte[] jpeg(int size) {
		return R2StorageMinioIntegrationTest.jpeg(size);
	}

	private JsonNode ticket(String productId, String bearer, String contentType, long size) throws Exception {
		var result = post("/api/v1/admin/products/" + productId + "/images/uploads", Map.of("contentType", contentType, "sizeBytes", size), bearer, "k-" + UUID.randomUUID()).andExpect(status().isCreated()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private int upload(JsonNode ticket, String contentType, byte[] body) throws Exception {
		var target = ticket.get("upload");
		var request = HttpRequest.newBuilder(URI.create(target.get("url").asString())).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		target.get("headers").properties().forEach(e -> request.header(e.getKey(), contentType != null ? contentType : e.getValue().asString()));
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private JsonNode addImage(String productId, String bearer, int size) throws Exception {
		var t = ticket(productId, bearer, "image/jpeg", size);
		assertThat(upload(t, null, jpeg(size))).isEqualTo(200);
		var result = post("/api/v1/admin/products/" + productId + "/images/uploads/" + t.get("uploadId").asString() + "/complete", Map.of(), bearer, null).andExpect(status().isOk()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void theAdapterIsTheR2OneAndNotTheFailClosedDefault() {
		assertThat(storage.getClass().getSimpleName()).isEqualTo("R2ObjectStorage");
	}

	@Test
	void onlyStaffCanManageImages() throws Exception {
		var product = stockedMattress(1);
		var body = Map.of("contentType", "image/jpeg", "sizeBytes", 100);
		post("/api/v1/admin/products/" + product.id() + "/images/uploads", body, null, null).andExpect(status().isUnauthorized());
		post("/api/v1/admin/products/" + product.id() + "/images/uploads", body, token(UUID.randomUUID()), null).andExpect(status().isForbidden());
		get("/api/v1/admin/products/" + product.id() + "/images", token(UUID.randomUUID())).andExpect(status().isForbidden());
		send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/products/" + product.id() + "/images/" + UUID.randomUUID()), token(UUID.randomUUID())).andExpect(status().isForbidden());
	}

	@Test
	void theWholeFlow_ticketUploadCompleteListReorderDelete_includingTheStoredObject() throws Exception {
		var product = stockedMattress(1);
		var staff = newStaffToken();
		var pid = product.id().toString();

		var first = addImage(pid, staff, 3000);
		var second = addImage(pid, staff, 4000);
		assertThat(first.get("url").asString()).startsWith(MinioSupport.endpoint() + "/" + BUCKET + "/product-images/" + pid + "/").endsWith(".jpg");
		assertThat(first.get("position").asInt()).isZero();
		assertThat(second.get("position").asInt()).isEqualTo(1);

		var list = JSON.readTree(get("/api/v1/admin/products/" + pid + "/images", staff).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(list.get("items")).hasSize(2);
		// the public product shows them in order
		get("/api/v1/products/" + pid, null).andExpect(status().isOk()).andExpect(jsonPath("$.images.length()").value(2)).andExpect(jsonPath("$.images[0].id").value(first.get("id").asString()));

		// reorder: a permutation is accepted, anything else is not
		var swapped = List.of(second.get("id").asString(), first.get("id").asString());
		put("/api/v1/admin/products/" + pid + "/images/order", Map.of("imageIds", swapped), staff).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second.get("id").asString())).andExpect(jsonPath("$.items[0].position").value(0));
		put("/api/v1/admin/products/" + pid + "/images/order", Map.of("imageIds", List.of(first.get("id").asString())), staff).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("images_order_invalid"));
		put("/api/v1/admin/products/" + pid + "/images/order", Map.of("imageIds", List.of(first.get("id").asString(), first.get("id").asString())), staff).andExpect(status().isUnprocessableContent());
		put("/api/v1/admin/products/" + pid + "/images/order", Map.of("imageIds", List.of(UUID.randomUUID().toString(), first.get("id").asString())), staff).andExpect(status().isUnprocessableContent());

		// the object exists; deleting the image deletes exactly that object and renumbers the rest
		var firstKey = first.get("url").asString().substring((MinioSupport.endpoint() + "/" + BUCKET + "/").length());
		var secondKey = second.get("url").asString().substring((MinioSupport.endpoint() + "/" + BUCKET + "/").length());
		assertThat(objects().inspect(firstKey, 4)).isPresent();
		send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/products/" + pid + "/images/" + first.get("id").asString()), staff).andExpect(status().isNoContent());
		assertThat(objects().inspect(firstKey, 4)).as("the deleted image's object is gone").isEmpty();
		assertThat(objects().inspect(secondKey, 4)).as("the other image's object is untouched").isPresent();
		get("/api/v1/admin/products/" + pid + "/images", staff).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(second.get("id").asString())).andExpect(jsonPath("$.items[0].position").value(0));
		send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/products/" + pid + "/images/" + first.get("id").asString()), staff).andExpect(status().isNotFound());
	}

	@Test
	void completingWithoutUploadingIsRefused_andAFileThatIsNotWhatWasDeclaredIsRejectedAndDeleted() throws Exception {
		var product = stockedMattress(1);
		var staff = newStaffToken();
		var pid = product.id().toString();

		var empty = ticket(pid, staff, "image/jpeg", 500);
		post("/api/v1/admin/products/" + pid + "/images/uploads/" + empty.get("uploadId").asString() + "/complete", Map.of(), staff, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("upload_missing"));

		var png = ticket(pid, staff, "image/jpeg", 1000);       // PNG bytes under a JPEG declaration
		assertThat(upload(png, null, R2StorageMinioIntegrationTest.png(1000))).isEqualTo(200);
		post("/api/v1/admin/products/" + pid + "/images/uploads/" + png.get("uploadId").asString() + "/complete", Map.of(), staff, null)
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("upload_signature_mismatch"));
		get("/api/v1/admin/products/" + pid + "/images", staff).andExpect(jsonPath("$.items.length()").value(0));
		// the rejected object was deleted from the bucket
		assertThat(objectsUnder(pid)).isEmpty();
	}

	private List<String> objectsUnder(String productId) {
		// every object key of this product that still exists in the bucket (keys are generated by the server: product-images/<productId>/<uuid>.<ext>)
		var keys = new ArrayList<String>();
		try (var s3 = software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create(MinioSupport.endpoint())).region(software.amazon.awssdk.regions.Region.of("auto"))
				.credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(MinioSupport.ACCESS_KEY, MinioSupport.SECRET_KEY)))
				.serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder()).build()) {
			s3.listObjectsV2(r -> r.bucket(BUCKET).prefix("product-images/" + productId + "/")).contents().forEach(o -> keys.add(o.key()));
		}
		return keys;
	}

	@Test
	void anUploadOfAnotherProductOrAnotherStaffMemberLooksLikeAMissingOne() throws Exception {
		var product = stockedMattress(1);
		var other = stockedMattress(1);
		var staff = newStaffToken();
		var t = ticket(product.id().toString(), staff, "image/jpeg", 800);
		assertThat(upload(t, null, jpeg(800))).isEqualTo(200);
		var path = "/api/v1/admin/products/%s/images/uploads/%s/complete";
		post(path.formatted(other.id(), t.get("uploadId").asString()), Map.of(), staff, null).andExpect(status().isNotFound());         // wrong product: nothing is attached anywhere
		post(path.formatted(product.id(), t.get("uploadId").asString()), Map.of(), newStaffToken(), null).andExpect(status().isNotFound()); // another staff member
		get("/api/v1/admin/products/" + other.id() + "/images", staff).andExpect(jsonPath("$.items.length()").value(0));
		get("/api/v1/admin/products/" + product.id() + "/images", staff).andExpect(jsonPath("$.items.length()").value(0));
		post(path.formatted(product.id(), t.get("uploadId").asString()), Map.of(), staff, null).andExpect(status().isOk());              // the right product and the requester: attached
	}

	@Test
	void validationAndLimits() throws Exception {
		var product = stockedMattress(1);
		var staff = newStaffToken();
		var base = "/api/v1/admin/products/" + product.id() + "/images/uploads";
		post(base, Map.of("contentType", "image/jpeg"), staff, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("sizeBytes"));
		post(base, Map.of("contentType", "application/pdf", "sizeBytes", 100), staff, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("upload_type_not_allowed"));
		post(base, Map.of("contentType", "image/jpeg", "sizeBytes", 100_001), staff, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("upload_too_large"));
		post(base, Map.of("contentType", "image/jpeg", "sizeBytes", 0), staff, null).andExpect(status().isUnprocessableContent());
		post("/api/v1/admin/products/" + UUID.randomUUID() + "/images/uploads", Map.of("contentType", "image/jpeg", "sizeBytes", 100), staff, null).andExpect(status().isNotFound());
	}

	@Test
	void completionRecordsAnAuditEntryAndAnOutboxEventWithoutTheStoragePath() throws Exception {
		var product = stockedMattress(1);
		var staff = newStaffToken();
		var image = addImage(product.id().toString(), staff, 2500);

		dispatcher.dispatchOnce();
		var events = capture.messages.stream().filter(m -> m.aggregateId().equals(product.id())).toList();
		assertThat(events).hasSize(1);
		assertThat(events.getFirst().type()).isEqualTo("product.image.uploaded");
		assertThat(events.getFirst().payload()).containsEntry("imageId", image.get("id").asString()).containsEntry("contentType", "image/jpeg");
		assertThat(events.getFirst().payload().toString()).doesNotContain("product-images/");
		assertThat(audits.findAll().stream().anyMatch(a -> "PRODUCT_IMAGE_ADDED".equals(a.getAction()) && product.id().toString().equals(a.getTargetId()))).isTrue();
	}
}
