# Product image storage: Cloudflare R2

Product images live in a **Cloudflare R2** bucket. The browser (admin area) uploads **directly** to R2 with a short-lived presigned `PUT` URL; the backend never receives the file, never
holds a browser-facing credential, and checks what was really stored before it attaches the image. Customers load the images from a **public domain** in front of the bucket.
**Supabase Storage is deliberately not used** (decision of the project owner; Supabase is used for Auth and PostgreSQL only).

Code: `infra.storage.R2ObjectStorage` implements the core port `ObjectStorage` (ADR 0007 describes the lifecycle, ADR 0014 the choices below). It uses the AWS SDK for Java v2 `s3` module
(R2 speaks the S3 protocol) with the lightweight URLConnection HTTP client. Without a complete configuration the application keeps the fail-closed `UnconfiguredObjectStorage`
(uploads answer 503 `storage_not_configured`).

## 1. What R2 provides (from Cloudflare's documentation; each line cites its page)

| Fact | Source |
|---|---|
| S3-compatible API at `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`; clients must set the region to `auto` (an empty value or `us-east-1` is aliased to `auto` for tools that cannot set it) | [Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/), [S3 API](https://developers.cloudflare.com/r2/api/s3/api/) |
| Presigned URLs: `GET`, `PUT`, `HEAD`, `DELETE`; expiry **1 second to 7 days** (604 800 s); reusable until they expire; must be defined with an expiry | [Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/) |
| Presigned URLs work **only on the `r2.cloudflarestorage.com` S3 domain, not on custom domains** | [Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/) |
| If `Content-Type` is part of the signature, a request with a different `Content-Type` fails; headers a browser sends must be allowed by CORS (`AllowedHeaders`) | [Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/), [CORS](https://developers.cloudflare.com/r2/buckets/cors/) |
| Expired presigned URL: `403 ExpiredRequest`, **without CORS headers** (the browser cannot read the body) | [CORS](https://developers.cloudflare.com/r2/buckets/cors/) |
| `HeadObject`, `GetObject` (with `Range`), `PutObject`, `DeleteObject`/`DeleteObjects` implemented; `HeadObject` ignores `Range`; no object lock, tagging, ACLs, SSE headers (SSE-C yes) | [S3 API compatibility](https://developers.cloudflare.com/r2/api/s3/api/) |
| Checksums: `CRC-64/NVME` supported for full objects; CRC32/CRC32C/SHA-1/SHA-256 only as composite (multipart) checksums | [S3 API compatibility](https://developers.cloudflare.com/r2/api/s3/api/) |
| Incomplete multipart uploads are aborted automatically after **7 days** by default; a lifecycle rule `AbortIncompleteMultipartUpload` (`DaysAfterInitiation`, optional prefix) changes that | [Upload objects](https://developers.cloudflare.com/r2/objects/upload-objects/), [Object lifecycles](https://developers.cloudflare.com/r2/buckets/object-lifecycles/) |
| API tokens: **Object Read & Write** can be limited to specific buckets; it works with the S3-compatible API only | [Authentication](https://developers.cloudflare.com/r2/api/tokens/) |
| Public access: a **custom domain** is the production option (WAF, caching, access controls); the `r2.dev` URL is **rate-limited and for development only** | [Public buckets](https://developers.cloudflare.com/r2/buckets/public-buckets/), [Limits](https://developers.cloudflare.com/r2/platform/limits/) |

What these pages **do not settle** (so this repository does not claim it): whether R2 rejects a body whose length differs from a **signed `Content-Length`** the way MinIO does, and the
exact error bodies. The adapter signs `Content-Length` anyway and **never relies on it**: `UploadService.completeProductImage` re-checks the stored size, content type and file signature.
`R2RealIntegrationTest.oversizedBodyIsRefusedBecauseContentLengthIsSigned` records the real answer on your bucket (section 7).

## 2. How the adapter uses it

1. `POST /api/v1/admin/products/{id}/images/uploads` (staff): the server validates type (`image/jpeg|png|webp`) and size (1 byte to `R2_MAX_UPLOAD_SIZE_BYTES`), generates the object key
   `product-images/<productId>/<uuid>.<ext>` (the client never chooses a key), stores an upload intent and returns a presigned PUT: signed headers `host`, **`content-type`** and
   **`content-length`** (= the declared size); no checksum headers (`WHEN_REQUIRED`: the SDK default CRC32 would add an `x-amz-checksum-*` requirement a browser `fetch` cannot meet).
2. The browser `PUT`s the file to the returned URL with the returned `Content-Type` header (the browser sets `Content-Length` from the body).
3. `POST .../uploads/{uploadId}/complete`: `HEAD` + ranged `GET` of the first 16 bytes: size within the declared limit, stored type equal to the declared type, **file signature (magic bytes)
   matches the type** (a PNG declared as JPEG is rejected and its object deleted). Then the image is attached, audited (`PRODUCT_IMAGE_ADDED`) and the outbox event
   `product.image.uploaded` is recorded in the same transaction.
4. `DELETE .../images/{imageId}` removes the image from the product and deletes **exactly its** object (`product_images.object_key`). Nothing can replace another image's object: every
   ticket has a new key. If the object delete fails the image is still removed and `PRODUCT_IMAGE_OBJECT_DELETE_FAILED` is audited for a manual sweep.
5. A scheduled job (`ana.upload.cleanup-interval`, default 15 min) calls `UploadService.expireAbandoned()`: intents never completed (older than `ana.upload.abandoned-after`, default 1 h
   after the URL expired) become EXPIRED and their objects are deleted. Safe to run on several instances (deleting is idempotent).
6. Storage failures surface as `503 storage_unavailable` with a fixed message (the SDK message and URLs are never returned or logged; only the exception class is logged).

## 3. Environment

| Variable | Meaning |
|---|---|
| `R2_ACCOUNT_ID` | Cloudflare account id; the endpoint is `https://<id>.r2.cloudflarestorage.com` |
| `R2_ENDPOINT` | optional full endpoint override (a local S3-compatible server, a Cloudflare jurisdiction endpoint such as EU) |
| `R2_BUCKET_NAME` | the bucket |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | **secrets** of a token limited to this bucket (section 4). Never logged, never returned (`toString` redacts them, tested), never in a URL |
| `R2_PUBLIC_BASE_URL` | public origin the bucket is served from (section 6), no trailing slash needed |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS` | upload URL lifetime, default 600; 1 to 604 800 (the application refuses to start with another value when R2 is configured) |
| `R2_MAX_UPLOAD_SIZE_BYTES` | largest accepted image, default 5 242 880 (5 MiB) |

All eight map onto `ana.storage.r2.*` and `ana.upload.url-ttl` / `ana.upload.max-bytes`. **Only a complete set** (account or endpoint, bucket, both keys, public URL) creates the R2 adapter. Anything
less leaves uploads disabled; a half-set configuration is logged as an error naming the missing variables (never values) and, in profile `prod`, **stops the start-up**.

## 4. Create the bucket and a restricted token (once)
1. Cloudflare dashboard > R2 > **Create bucket** (name e.g. `ana-depot-images`, location: nearest to Haiti's users/hosting; the bucket is **not** public yet).
2. R2 > **Manage API tokens** > Create API token: permission **Object Read & Write**, **Apply to specific buckets only** = that bucket, optional IP restriction to your API hosts, a TTL if you rotate.
   Copy the *Access Key ID* and *Secret Access Key* (shown once) into the deployment's secret store as `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`. Do **not** use an account-wide admin token.
3. Use a **separate bucket and token** for automated checks (section 7).

## 5. CORS for browser uploads
The admin area `PUT`s from the browser to `<account>.r2.cloudflarestorage.com`, a different origin, so the bucket needs a CORS policy (bucket > Settings > CORS policy > JSON):
```json
[
  {
    "AllowedOrigins": ["https://<admin-and-storefront-origin>"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```
Use the exact origin(s) of the web app (add `http://localhost:3100` only on a development bucket). Reading images through the public domain needs no CORS for `<img>` tags.
Remember: an **expired** URL answers 403 without CORS headers, so the browser reports a generic network error; the admin UI should treat a failed PUT as "request a new upload".

## 6. Public reads
Decision and alternatives: [ADR 0014](adr/0014-cloudflare-r2-object-storage.md). Summary: serve images from a **custom domain** attached to the bucket (R2 > bucket > Settings > Public access >
Connect domain; the domain's DNS must be on Cloudflare), set `R2_PUBLIC_BASE_URL=https://<images-domain>`. `r2.dev` is acceptable only on a development bucket (rate limited, no caching/WAF).
The business must choose the actual domain name (**open decision**); no domain is invented here. Presigned GET is not used: product photos are public catalog content.

## 7. Verification

| What | How | State |
|---|---|---|
| Presign: host, path/virtual-host style, region `auto`, `X-Amz-Expires`, signed `content-type` + `content-length`, no checksum parameters, no secret in the URL | `R2PresignTest` (local computation) | automated |
| Upload/HEAD/ranged GET/DELETE over real HTTP: valid JPEG, wrong content type, missing content type, oversize and undersize body, expired URL, tampered key, PNG declared JPEG, missing object, idempotent delete, unreachable store, wrong credentials | `R2StorageMinioIntegrationTest` against **MinIO** (a real S3-compatible server in Testcontainers, image `bitnamilegacy/minio:2025.7.23-debian-12-r3` because `minio/minio` is no longer on Docker Hub) | automated. **S3-compatible (MinIO), NOT real Cloudflare R2** |
| The staff endpoints end to end with the real adapter (ticket, PUT, complete, list, reorder, delete + object gone, rejection deletes the object, authz, validation, audit, outbox event) | `AdminProductImageApiIntegrationTest` (MinIO) | automated. **MinIO, not R2** |
| Abandoned upload cleanup deletes the object | `R2AbandonedUploadCleanupIntegrationTest` (MinIO) | automated. **MinIO, not R2** |
| **Real R2**: all of the above that can differ (signed header enforcement, errors, public domain) | `R2RealIntegrationTest`, opt-in, see below | **PENDING**: not run in this repository's development (no R2 credentials were available) |

Run the real check against a **test bucket** with a restricted token (it writes under `ana-depot-integration-test/<uuid>/` and deletes what it wrote):
```bash
R2_TEST_ENABLED=true R2_ACCOUNT_ID=... R2_BUCKET_NAME=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... \
R2_PUBLIC_BASE_URL=https://<images-domain> mvn test -Dtest=R2RealIntegrationTest
```
Record the date and the outcome of `oversizedBodyIsRefusedBecauseContentLengthIsSigned` here when you run it.

**Manual checklist before the first production upload**
- [ ] bucket created, **not** public; token limited to it; secrets only in the secret store
- [ ] CORS policy saved with the exact web origin; a browser PUT from the admin UI succeeds and from another origin fails
- [ ] custom domain connected; `R2_PUBLIC_BASE_URL` set; an uploaded image loads in the storefront; an object that is not an image of ours is not listed anywhere
- [ ] lifecycle rule for incomplete multipart uploads present (default 7 days; this application never starts a multipart upload, so this is hygiene for other tools, e.g. `rclone`)
- [ ] `R2RealIntegrationTest` run on a test bucket and its results written above
- [ ] quota/alerts configured in Cloudflare; token rotation date noted
- [ ] a staff member uploads, reorders and deletes an image through the admin UI; the object is gone from the bucket after delete
