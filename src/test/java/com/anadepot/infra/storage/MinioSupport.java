package com.anadepot.infra.storage;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * A real MinIO server (S3-compatible) shared by the storage tests of one test run. IMPORTANT FOR HONEST REPORTING: this proves the adapter against the S3 protocol
 * (SigV4 presigned PUT, HEAD, ranged GET, DELETE). It is NOT Cloudflare R2: R2's own behaviours (signed-header enforcement details, CORS, public domain, error bodies)
 * are only verified by the opt-in {@link R2RealIntegrationTest}.
 */
public final class MinioSupport {

	public static final String ACCESS_KEY = "minio-test-access";
	public static final String SECRET_KEY = "minio-test-secret-0123456789";
	/** The community image Bitnami kept after Docker Hub removed minio/minio (a genuine MinIO server build). */
	private static final String IMAGE = "bitnamilegacy/minio:2025.7.23-debian-12-r3";

	private static final GenericContainer<?> MINIO = new GenericContainer<>(IMAGE)
			.withEnv("MINIO_ROOT_USER", ACCESS_KEY).withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
			.withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

	static {
		MINIO.start();
	}

	private MinioSupport() {
	}

	public static String endpoint() {
		return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
	}

	/** Creates (idempotently) and returns the name of a fresh bucket. */
	public static String newBucket(String prefix) {
		var bucket = prefix + "-" + Long.toString(System.nanoTime(), 36);
		try (var s3 = S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.of("auto"))
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
			s3.createBucket(b -> b.bucket(bucket));
		}
		return bucket;
	}

	public static R2ObjectStorage storage(String bucket) {
		return new R2ObjectStorage(endpoint(), "auto", bucket, ACCESS_KEY, SECRET_KEY, endpoint() + "/" + bucket, true);
	}
}
