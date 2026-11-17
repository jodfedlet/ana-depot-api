# Operations: reliability, observability, backup and recovery

What is **implemented and verified locally** is marked so; what depends on the hosted Supabase project, Cloudflare R2 or a monitoring service is a **procedure or a proposal that still has to be confirmed there**. No monitoring system, alert receiver or backup schedule exists yet: this document defines what to deploy.

## 1. Timeouts (every wait is bounded)
| Layer | Setting | Value | Where |
|---|---|---|---|
| DB pool | wait for a connection / validate | 5 s / 3 s | `spring.datasource.hikari.connection-timeout`, `validation-timeout` |
| DB driver | TCP connect / login / socket (hard backstop) | 5 s / 10 s / 60 s, TCP keep-alive | `hikari.data-source-properties` |
| DB transaction | every transaction, so every statement and lock wait inside it | 20 s | `spring.transaction.default-timeout` |
| HTTP server | idle connection / keep-alive / async request | 10 s / 20 s / 30 s | `server.tomcat.*`, `spring.mvc.async.request-timeout` |
| Object storage (R2) | connect / socket / per attempt / whole call | 3 s / 8 s / 8 s / 15 s | `R2ObjectStorage` |
| Outbox handler | lease on a claimed event (then claimed again) | 1 min | `ana.outbox.lease-duration` |
| Browser to API | per attempt | 10 s | `ana-depot-web/src/lib/api/http.ts` |

Retries: the browser retries **GET only**, at most 2 times, on network error, timeout, 502/503/504, with exponential backoff and full jitter (not on 429, not on 4xx). A write is never retried automatically by the client: the screen retries it with the **same `Idempotency-Key`** (ADR 0004), so a lost answer cannot apply it twice (verified in the browser suites). The outbox retries with exponential backoff plus jitter up to a limit, then DEAD (ADR 0005).

## 2. Graceful degradation
* Object storage down or not configured: uploads and image completion answer **503 `storage_unavailable`** / `storage_not_configured`; the catalog, reservations, payments and reports keep working. Verified: tests (`StorageHealthIndicatorTest`, `R2StorageMinioIntegrationTest`) and a local run against an unreachable store (catalog 200, upload completion 503 in 0.4 s).
* Database down: readiness goes DOWN (traffic is withdrawn); liveness stays UP so the platform does not restart a healthy process.
* Rate limiter and idempotency use PostgreSQL, so they share the database's fate; there is no second dependency.

## 3. Health
| Endpoint | Meaning | Used by |
|---|---|---|
| `/actuator/health/liveness` | the process is alive (never depends on the database) | restart decisions |
| `/actuator/health/readiness` | database reachable | load balancer routing |
| `/actuator/health/dependencies` | database **and** object storage (probe cached 30 s; "not configured" is UNKNOWN, not DOWN) | humans, dashboards; NOT for routing |

Health answers carry **no details** (status only) and are public.

## 4. Metrics (Micrometer)
Staff-only on the API port (`/actuator/metrics/**` with a staff token). For a scraper, bind a **private management port** (`MANAGEMENT_SERVER_PORT`, e.g. `8081`, with `MANAGEMENT_SERVER_ADDRESS` on an internal interface; never published to the internet): there `/actuator/prometheus`, `/actuator/metrics/**` and `/actuator/health/**` answer without a token and everything else is denied (`ManagementSecurityConfig`). On the public port `/actuator/prometheus` is always refused. Verified by `ManagementPortIntegrationTest` and by a local run.

| Prometheus series | Meaning |
|---|---|
| `http_server_requests_seconds_{count,sum,bucket}{uri,status,method,outcome}` | latency and error rate per route template (buckets at 100 ms, 300 ms, 1 s, 3 s; histogram for p95/p99) |
| `hikaricp_connections_{active,idle,pending,max}`, `hikaricp_connections_timeout_total` | pool saturation |
| `anadepot_outbox_pending`, `anadepot_outbox_dead`, `anadepot_outbox_oldest_undelivered_age_seconds` | backlog, dead letters, age of the oldest undelivered event |
| `anadepot_outbox_events_total{result=delivered\|failed\|dead}` | delivery outcomes |
| `anadepot_ratelimit_total{operation,result=blocked}` | requests refused by the limiter |
| `anadepot_idempotency_total{result=replayed\|race\|key_reuse}`, `anadepot_dedup_total` | idempotent replays and duplicate events |
| `anadepot_operations_total{operation,currency}` | reservations/payments by type (per currency, never summed) |
| `jvm_*`, `process_*`, `system_*` | runtime |

## 5. Correlation ids and logs
`X-Correlation-Id` from the browser (the web client generates one per call) is accepted only when well formed (otherwise replaced; header injection is tested), echoed in the response header and in the `correlationId` of every problem+json, put in the logging MDC, and stored on audit events, ledger entries and outbox events. Logs are structured JSON, **one line per request**: method, route path, status, duration; never the query string, tokens, phone numbers or references. `ObservabilityIntegrationTest` captures the real log output of lookups with phone and reference, bearer tokens and search queries and asserts none of them appears. Domain records that hold personal data have redacting `toString()` (Customer, Contact, customer DTOs).

Following one failure: the browser shows/report the `correlationId` (the client error hook includes it) -> search the logs for it -> the same id is on the audit/ledger/outbox rows.

## 6. Alert rules (PROPOSED: nothing evaluates them yet)
For Prometheus (or any system that can evaluate these expressions). Thresholds are starting points to tune after the first weeks of real traffic.
```yaml
groups:
- name: ana-depot-api
  rules:
  - alert: ApiDown
    expr: up{job="ana-depot-api"} == 0
    for: 2m
    labels: {severity: page}
  - alert: ApiNotReady                      # database unreachable from an instance
    expr: probe_success{probe="ana-readiness"} == 0
    for: 2m
    labels: {severity: page}
  - alert: High5xxRate
    expr: sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.02
    for: 5m
    labels: {severity: page}
  - alert: SlowRequestsP95
    expr: histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m]))) > 1
    for: 10m
    labels: {severity: ticket}
  - alert: DbPoolSaturated
    expr: hikaricp_connections_pending > 0 or hikaricp_connections_active / hikaricp_connections_max > 0.9
    for: 5m
    labels: {severity: ticket}
  - alert: OutboxDeadLetters
    expr: anadepot_outbox_dead > 0
    for: 1m
    labels: {severity: ticket}
  - alert: OutboxBacklogAge                 # the dispatcher or a handler is stuck even if nothing is DEAD yet
    expr: anadepot_outbox_oldest_undelivered_age_seconds > 300
    for: 5m
    labels: {severity: ticket}
  - alert: RateLimitSpike                   # abuse, or a limit that is too tight for real traffic
    expr: sum(rate(anadepot_ratelimit_total[10m])) > 1
    for: 10m
    labels: {severity: ticket}
  - alert: StorageDependencyDown            # uploads fail with 503; the rest works
    expr: probe_success{probe="ana-dependencies"} == 0
    for: 10m
    labels: {severity: ticket}
```
Not covered by metrics: database free space and backup freshness (monitor in the Supabase dashboard / the backup job itself).

## 7. Frontend error monitoring
`ana-depot-web/src/lib/observability/report.ts` is the single integration point and has **no vendor dependency**: until a service is chosen, reports go to `console.error`. A report contains only the error class, our stable `code`, HTTP status, correlation id and the route path (no query string); never tokens, form values or personal data (tested). To adopt Sentry or similar, register one sink with `setErrorSink` and configure the vendor's own scrubbing (no request bodies, no cookies, no IP storage unless decided). **A vendor has not been chosen.**

## 8. Backup and recovery

### Objectives (PROPOSED: the owner must approve them)
| | Proposal | Needs |
|---|---|---|
| RPO (data that may be lost) | at most **5 minutes** of orders/payments | Point-in-time recovery (PITR). Daily backups alone give an RPO of up to 24 h. |
| RTO (time to be serving again) | **4 hours** during business days | a rehearsed runbook (below) and someone on call |
The cost of PITR and the retention windows depend on the **Supabase plan**: *confirm in the hosted project (Dashboard > Database > Backups) which backups exist, how long they are kept and whether PITR is enabled.* This document does not assume it is.

### PostgreSQL (Supabase)
1. Hosted backups (daily and/or PITR) are the primary mechanism. Verify they are enabled and note the retention.
2. Independent logical backup (protects against account or project loss and lets us test restores): scheduled `pg_dump -Fc --schema=<app schema>` with the **direct** connection of a read-only role, written to storage in another account/provider, encrypted, retained e.g. 30 days. Set a freshness alert on the job (missing dump = alert).
3. **Before every production migration**: take a fresh dump (or confirm a PITR point just before) and write down the time.

### Restore runbook
1. Declare the incident, stop writes if the data is suspect (take the API out of rotation: readiness DOWN or scale to 0).
2. Restore into a **new** database/project (never over the damaged one first): hosted PITR/backup restore, or `pg_restore` of the latest dump into a scratch database with the owner roles created beforehand (`dev/restore-test.sh` does exactly this).
3. Point a staging instance at the copy; check Flyway validate passes, row counts, ledger reconciliation (`GET /admin/reservations/{id}/reconciliation`), the V11 lockdown (section 7 of `docs/supabase-database.md`), a catalog read.
4. Switch the production `SPRING_DATASOURCE_URL` to the restored database, restart, run the smoke checks (health, catalog, a staff sign-in).
5. Reconcile objects (below) and record the incident, the data-loss window and the follow-ups.

### Tested restore (LOCAL; `dev/restore-test.sh`)
The script is repeatable and does not alter the source. Result of the last run (local Docker, Supabase Postgres 17.6 image): **21 checks passed, 0 failed, 125 s total**.
* A. Fresh database: all **13 migrations (V1 to V13)** apply from empty and the app starts with `ddl-auto=validate` (34 s); readiness UP; catalog 200; Data API lockdown holds (RLS on every application table, no policies, no grants to `anon`/`authenticated`/`service_role`, `anon` really cannot read).
* B. Dump of the fresh database restored into a separate database: identical row counts, lockdown **still holds after the restore**, the app starts on it (Flyway validate + Hibernate validate).
* C. Dump of the real local data (31 tables, 3,265 rows, 324 kB: 71 reservations, 46 payments, 56 ledger entries) restored into a separate database in **2.2 s** (dump + restore): identical row counts in all 31 tables; no orphan ledger rows or reservation lines; ledger totals identical per currency (HTG 47,400.00 and USD 238.00, never combined); the app starts on the copy in 28 s; the public catalog and categories match the restored rows; the staff row is recognised; the reconciliation endpoint reports **28 reservations with payments, 0 inconsistent**; a staff report answers.
* Two real findings while building it, now in the script: the owner roles must exist before `pg_restore` (objects keep their owner) and the application role needs `CREATE` on the schema it owns objects in.

**What this does NOT prove:** hosted Supabase backups, PITR or their restore times; restore time or size at production volume (this data set is tiny); Cloudflare R2 object recovery; recovery in another region; that a human can follow the runbook under pressure (rehearse it).

### Object storage (Cloudflare R2)
* Product images are **derived, replaceable assets**; the database stores their object keys. DB and bucket can drift after a restore (rows pointing at objects that were never written, or objects with no row).
* Recommended on the real bucket (to confirm in the Cloudflare dashboard): check in Cloudflare's current documentation which protections R2 offers (object versioning, bucket lock, lifecycle rules) and do not assume them; in any case keep a periodic copy of the bucket in a second bucket/provider, restrict the API token to the one bucket, and keep deletion rights narrow.
* **Reconciliation after a restore** (procedure; not automated yet): list `product_images.object_key` and list the bucket prefix `product-images/`; missing objects -> re-upload through the admin or deactivate the image row; objects without a row older than the upload TTL -> safe to delete. Abandoned uploads are already cleaned by the scheduler (`ana.upload.abandoned-after`).
* Not verified: any real R2 recovery. MinIO stands in locally.

## 9. Deployment rollback and migration safety
* The application is stateless: a release is rolled back by redeploying the previous jar. This is safe **only** if the schema is compatible with both versions, hence:
* **Flyway migrations are immutable.** Never edit, rename, renumber or delete a released `V*__*.sql`; fix forward with the next version. **Never `flyway repair`**, never disable validation, never edit the history table.
* **Expand/contract.** Release N adds columns/tables nullable or with defaults (expand) and still works with the old code; release N+1 starts using them; a later release removes what nothing uses (contract). Never drop or rename a column in the same release that stops using it.
* Backup (or confirmed PITR point) before every production migrate; migrations run at start-up, so the first instance to start performs them (Flyway's advisory lock serialises instances).
* Every new table enables row level security in its own migration (`MigrationRlsConventionTest`); V11 stays the lockdown.
* If a migration fails half-way (PostgreSQL DDL is transactional, so it normally leaves nothing): fix forward with a corrective migration, or restore from the pre-migration backup. Do not hand-edit `flyway_schema_history`.
* Web rollback: redeploy the previous build; the API contract is additive, and the service worker cache version changes per build.

## 10. Runbooks (short)
* **5xx spike**: check `/actuator/health/dependencies`, DB pool metrics, then logs by `correlationId`; roll back the last release if it coincides.
* **Outbox DEAD > 0**: read `last_error` of the dead rows, fix the cause, re-queue; events are delivered at least once and consumers deduplicate.
* **Backlog age growing**: the dispatcher is stuck (lease/handler); restart the instance; check the DB for locks.
* **DB pool saturated**: look for slow queries (transaction timeout is 20 s), then raise the pool within the plan's connection limit.
* **Storage down**: uploads fail with 503 by design; check R2 status and credentials; nothing else is affected.

## 11. Not verified / decisions needed
Hosted Supabase backup/PITR configuration and restore time · real R2 recovery · an alerting system (none deployed; rules above are untested expressions) · a frontend error-monitoring vendor · owner approval of RPO/RTO · who is on call.
