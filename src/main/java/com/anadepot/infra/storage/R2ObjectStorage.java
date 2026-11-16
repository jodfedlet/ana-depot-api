package com.anadepot.infra.storage;

import com.anadepot.core.catalog.repository.ObjectStorage;
import com.anadepot.core.shared.DomainRuleViolation;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link ObjectStorage} on Cloudflare R2 through its S3-compatible API (ADR 0014, docs/storage-r2.md). The browser uploads DIRECTLY with a presigned PUT; the credentials
 * that sign it never leave this class and are never logged or returned.
 *
 * <ul>
 *   <li>Region {@code auto}, endpoint {@code https://<account>.r2.cloudflarestorage.com}; path-style addressing by default.</li>
 *   <li>The presigned URL signs {@code Content-Type} AND {@code Content-Length} (the declared size): a different type or size does not match the signature.
 *       The same checks run again after the upload in {@code UploadService.completeProductImage} (HEAD + first bytes), because the signature only protects the upload itself.</li>
 *   <li>Checksum headers are not added ({@code WHEN_REQUIRED}): the SDK's default CRC32 would put an {@code x-amz-checksum-*} requirement into every URL, which a browser {@code fetch} would not satisfy.</li>
 *   <li>HTTP: the JDK-based URLConnection client with short timeouts; every storage failure surfaces as {@code storage_unavailable} (503), never as an SDK stack trace.</li>
 * </ul>
 */
public class R2ObjectStorage implements ObjectStorage, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(R2ObjectStorage.class);
	/** Cloudflare R2: a presigned URL may be valid from 1 second to 7 days. */
	public static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

	private final S3Client client;
	private final S3Presigner presigner;
	private final String bucket;
	private final String publicBaseUrl;

	public R2ObjectStorage(String endpoint, String region, String bucket, String accessKeyId, String secretAccessKey, String publicBaseUrl, boolean pathStyle) {
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
		var s3 = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
		this.bucket = bucket;
		this.publicBaseUrl = publicBaseUrl.strip().replaceAll("/+$", "");
		this.presigner = S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).credentialsProvider(credentials).serviceConfiguration(s3).build();
		this.client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).credentialsProvider(credentials).serviceConfiguration(s3)
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
				.httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(8)))
				.overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(8)))
				.build();
	}

	@Override
	public PresignedUpload presignPut(String objectKey, String contentType, long maxBytes, Duration ttl) {
		if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(MAX_PRESIGN_TTL) > 0) {
			throw new DomainRuleViolation("storage_configuration_invalid", "A presigned URL must be valid between 1 second and 7 days");
		}
		try {
			var put = PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).contentLength(maxBytes).build();
			var presigned = presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
			// what the CLIENT must send: the signed Content-Type (Content-Length is set by the browser from the body; `host` by the HTTP stack)
			var headers = new LinkedHashMap<String, String>();
			presigned.signedHeaders().forEach((name, values) -> {
				if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length") && !values.isEmpty()) {
					headers.put(canonical(name), values.getFirst());
				}
			});
			return new PresignedUpload(presigned.url().toString(), "PUT", headers, presigned.expiration());
		} catch (RuntimeException e) {
			throw unavailable("presign", e);
		}
	}

	private static String canonical(String header) {
		return header.equalsIgnoreCase("content-type") ? "Content-Type" : header;
	}

	@Override
	public Optional<StoredObject> inspect(String objectKey, int headBytes) {
		try {
			var head = client.headObject(r -> r.bucket(bucket).key(objectKey));
			long size = head.contentLength() == null ? 0 : head.contentLength();
			byte[] first = size == 0 ? new byte[0] : client.getObjectAsBytes(r -> r.bucket(bucket).key(objectKey).range("bytes=0-" + (Math.min(size, headBytes) - 1))).asByteArray();
			return Optional.of(new StoredObject(size, head.contentType(), first));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw unavailable("inspect", e);
		} catch (RuntimeException e) {
			throw unavailable("inspect", e);
		}
	}

	@Override
	public void delete(String objectKey) {
		try {
			client.deleteObject(r -> r.bucket(bucket).key(objectKey));     // S3 semantics: deleting a missing key succeeds
		} catch (RuntimeException e) {
			throw unavailable("delete", e);
		}
	}

	@Override
	public String publicUrl(String objectKey) {
		return publicBaseUrl + "/" + objectKey;
	}

	/** Never includes the SDK message (it can echo request details); the class name is enough to diagnose, and nothing secret is touched. */
	private static DomainRuleViolation unavailable(String operation, RuntimeException e) {
		log.warn("Object storage {} failed: {}", operation, e.getClass().getSimpleName());
		return new DomainRuleViolation("storage_unavailable", "Object storage is temporarily unavailable");
	}

	@Override
	public void close() {
		client.close();
		presigner.close();
	}

	@Override
	public String toString() {
		return "R2ObjectStorage[bucket=" + bucket + "]";
	}
}
