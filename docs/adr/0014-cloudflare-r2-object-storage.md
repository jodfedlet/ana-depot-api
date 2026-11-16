# ADR 0014 - Cloudflare R2 for product images: adapter, SDK and public domain
Status: accepted for the adapter and SDK; the **public domain name is an open business decision** (the mechanism is decided). Details and verification: [../storage-r2.md](../storage-r2.md).

**Context.** Product photos must be stored outside the database, uploaded by the admin straight from the browser, and served fast and cheaply to customers in Haiti. The project owner chose Cloudflare R2 and ruled out
Supabase Storage (Supabase is used for Auth and PostgreSQL only). R2 exposes an S3-compatible API and presigned URLs.

**Decision.**
1. **Port and adapter.** `core.catalog.repository.ObjectStorage` stays the port; `infra.storage.R2ObjectStorage` is the adapter; `StorageConfig` creates it only from a COMPLETE configuration and otherwise keeps the
   fail-closed `UnconfiguredObjectStorage`. Credentials are bucket-scoped (Object Read & Write on one bucket), live only in the environment, and are redacted from `toString`, logs and responses.
2. **AWS SDK for Java v2, `s3` module.** R2 documents the S3 API as its integration surface; the SDK implements SigV4 presigning and HEAD/GET/DELETE correctly (canonical URI/query/header rules are easy to get subtly wrong by hand, and a
   wrong signature fails only at upload time). Alternatives rejected: a hand-written SigV4 (security-sensitive code to maintain, no benefit), the Cloudflare REST API (it does not offer presigned object URLs and does not accept the S3 token type),
   MinIO's Java client (S3-generic but a second dependency tree with the same purpose).
3. **HTTP client: the URLConnection client (`url-connection-client`), not Apache or Netty.** The adapter makes only three kinds of calls, all small (HEAD, a 16-byte ranged GET, DELETE) and never moves a file (browsers upload to R2 directly), so the
   lightest client (no extra thread pools, no native/Netty stack, a few hundred KB) is enough; the default Apache client and the Netty client are excluded from the dependency tree. Timeouts are explicit (connect 3 s, socket 8 s, call 15 s).
4. **Presigned PUT signs `Content-Type` and `Content-Length`, nothing else, with no checksum headers.** The checksum default of recent SDK versions would add an `x-amz-checksum-*` requirement a browser `fetch` cannot satisfy, so it is
   `WHEN_REQUIRED`. The signature protects the upload; it does not prove the bytes are an image, so completion **re-verifies** size, stored content type and magic bytes (unchanged from ADR 0007) and deletes rejected objects.
   Whether R2 enforces a signed `Content-Length` exactly like MinIO does is **unverified without a real R2 run** (opt-in test); the design does not depend on it.
5. **Region `auto`, path-style addressing by default** (`https://<account>.r2.cloudflarestorage.com/<bucket>/<key>`; virtual-hosted style is supported and unit-tested).
6. **Lifetime** of upload URLs defaults to 10 minutes (`R2_PRESIGNED_URL_EXPIRATION_SECONDS`); R2 allows 1 s to 7 days and the application refuses a value outside that range.
7. **Public reads through a custom domain on Cloudflare**, configured as `R2_PUBLIC_BASE_URL`. Reasons: R2 documents the `r2.dev` URL as rate limited and for development only; presigned URLs do not work on custom domains and are the wrong tool for public catalog images
   (every page view would need a signature); a custom domain gives edge caching and WAF/bot rules. Images are public by design (they illustrate a public catalog); anything private would need a different bucket and presigned GET.
   **The domain name itself is not chosen here** - the owner must (open decision); until then development uses the `r2.dev` URL of a development bucket.
8. **Lifecycle of objects:** completed images are kept with their product; removing an image deletes exactly its object (`product_images.object_key`, unique); abandoned and rejected uploads are deleted (a scheduled cleanup and the
   completion step); the application never starts multipart uploads, so the bucket's abort-incomplete-multipart rule (default 7 days) is hygiene for other tools.
9. **Events and audit:** completing an upload records the audit entry `PRODUCT_IMAGE_ADDED` and the outbox event `product.image.uploaded` (payload: image id, content type, size - no storage path) in the same transaction as the attachment; removal records `PRODUCT_IMAGE_REMOVED` / `product.image.removed`.

**Consequences.** One adapter, one set of eight variables. CI proves the adapter against MinIO (S3-compatible, **not R2**); R2's own behaviour is verified only by `R2RealIntegrationTest`, which is **pending** until someone runs it with real credentials. The AWS SDK adds
about 8.5 MB to the executable jar (67.3 MB to 75.8 MB measured; the Apache and Netty clients are not in it). If Cloudflare changes S3 behaviour, the adapter is the only place to adapt. If the business later wants private images, the port gains a presigned-GET method; nothing else changes.
