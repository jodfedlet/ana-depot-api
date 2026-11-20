# ana-depot-api — Ana Dépôt backend

@AGENTS.md

Spring Boot 4 / Java 25 / Maven / PostgreSQL (Flyway + Spring Data JPA). **Modular monolith, minimal hexagonal architecture.**
Single-seller retail + wholesale store, launching in Haiti, selling in **HTG and USD**. Brand: Ana Dépôt (`ana-depot`). Sibling
project: `../ana-depot-web` (Next.js; being connected to this API: `docs/integration-map.md`).

Read `README.md` first, then `docs/api.md` (the implemented contract), `docs/architecture.md`, `docs/domain.md`, `docs/database.md`, `docs/auth.md`, `docs/search.md`, `docs/supabase-database.md`, `docs/storage-r2.md`, `docs/adr/`.

## Commands
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem   # the pom targets Java 25
mvn test            # unit + Testcontainers (PostgreSQL, MinIO) integration tests (needs Docker)
mvn package         # jar in target/
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run   # needs SPRING_DATASOURCE_PASSWORD (README); datasource comes from SPRING_DATASOURCE_*
```

## Architecture rules (enforced by `ArchitectureTest`)
- Exactly two top-level packages under `com.anadepot`: **`core`** and **`infra`**, then by bounded context
  (`catalog inventory reservation customer payment shared` / `… persistence security config web outbox ratelimit resilience storage`).
- `core` is plain Java: **no Spring, JPA, Jackson, servlet, Micrometer, provider SDKs**. Business rules live in `model`;
  `service` = interfaces, `serviceImpl` = implementations (`XxxServiceImpl`), `repository` = ports.
- `infra` has JPA entities (package-private, never in `core`), Spring Data repositories, adapters, controllers + DTOs, security, config.
  Core services are wired explicitly in `infra.config.CoreConfig`. The application class lives in `infra` on purpose.
- Persistence is JPA only. **No JdbcTemplate/JdbcClient/raw JDBC — also not in tests.** The schema belongs to Flyway
  (`ddl-auto=validate`); never edit a released `V*__*.sql`, add the next version.
- No JPA entity is ever returned by a controller; DTOs are separate.

## Business invariants (do not "simplify" these away)
- ONE product, independent prices per currency (`product_prices`), ONE stock position (`inventory_items`). No `currency` on the product.
- `Money` = `BigDecimal` + `SupportedCurrency`; cross-currency `plus/minus/compare` throws. No conversion, no exchange rate.
- Reservation totals/balances/payments are per currency; PAID only when all currencies settled; collection needs PAID.
- Ledger/payments/audit/movements are append-only (database triggers). Corrections = reversal/adjustment entries.
- Retriable writes need an idempotency key (payments, reversals, adjustments, stock adjustments: REQUIRED).
- Events go through the outbox in the SAME transaction (`DomainEventRecorder`, MANDATORY propagation), delivered at least once.
- Insert-only entities extend `InsertOnlyEntity` (so Spring Data `save` uses `persist`, never a silently-overwriting `merge`).
- Authorization = server-side `staff_members` row (role OWNER/MANAGER/STAFF), never token claims. Deny by default (`SecurityConfig`); every `/api/v1/admin` endpoint needs a permission listed in `AdminAccessRules` (unlisted = owner only; `AdminAccessMatrixIntegrationTest` walks every endpoint x role). A cash payment never exceeds the remaining balance of its currency (`overpayment_not_allowed`).

## Phase 3 rules worth knowing
- Customer endpoints return `CustomerReservationView` only (no internal notes, staff ids, ledger); lookup answers an IDENTICAL 404 for unknown reference / wrong phone. Guests are never merged into accounts (V9).
- Prices never come from clients. `POST /reservations` requires `Idempotency-Key`.
- Every new table must `enable row level security` in its own migration (V11 locked the Data API down; `MigrationRlsConventionTest`). The lockdown migration stays numbered after every table-creating migration.
- Search is PostgreSQL full-text (`ProductSearchNative` is the ONE native-SQL class, justified in ADR 0015). Product images live in Cloudflare R2 (never Supabase Storage); storage tests use MinIO, which is NOT R2.
- Secrets (`R2_SECRET_ACCESS_KEY`, DB password) come from the environment only and are never logged or returned; `.env` and `dev/supabase/signing_key.json` are never committed.

## Admin API rules (Phase 2)
- Staff controllers (`Admin*Controller`) only parse and shape; rules live in `core` (`*AdminService`, `*Draft`, `*Query`). Replacing endpoints require the `version` the caller read (409 `concurrent_update`); stock adjustments and every payment write require an `Idempotency-Key`.
- Every report/summary figure is **per currency**; never add HTG and USD, not even for a "total". Audit entries never contain personal text (note bodies, names, e-mails): record field names or lengths.
- New list filters are JPQL (`EntityManager`) except text search, which reuses `ProductSearchNative`. Staff-only figures (`onHand`, `reserved`) never appear in public DTOs.
- Domain codes that are state conflicts must be added to `GlobalExceptionHandler.CONFLICTS` (409), otherwise they answer 422. The list of staff codes is in `docs/api.md`.

## Testing
Integration tests extend `AbstractIntegrationTest` (one shared PostgreSQL container); staff HTTP tests are in `infra/admin` (`ApiTestSupport`, `newStaffToken()`), check money/counts as DELTAS around the data the test creates. Create uniquely named data; do not assert global
counts. JWT tests use locally generated keys — they are NOT Supabase integration tests (say so when reporting).

## Phase rules worth knowing (MVP closure)
- Staff MFA: `ANA_STAFF_REQUIRE_MFA` defaults to true in `prod`; recovery codes are member-bound SHA-256 hashes, single use, audited; a redeemed code opens THAT session (`session_id`) for `ANA_STAFF_RECOVERY_SESSION_TTL`.
- Reservations: `markReady` stamps the pickup deadline from `HoldPolicy`; `ReservationExpiryScheduler` reminds and expires; `POST /reservations/cancel` is the customer's own cancellation (pending/confirmed only). A refund done in the Stripe dashboard is only OBSERVED (`charge.refunded`); the ledger is never netted.
- Notifications: SUPERSEDED / EXPIRED are not failures; important types keep a longer limit and bypass the per-address throttle.
- Test hygiene: a stale ECJ class in `target/` (IDE) shows up as "Unresolved compilation problem": delete the package under `target/classes` and `target/test-classes`.

## Open business decisions — never invent defaults
Wholesale visibility; business-wide minimum order; final selling units; pack/box conversion; 
pickup details (the snapshot is opaque JSON); notification channel; legal pages; low-stock policy. (Decided and implemented: refunds are done in the Stripe dashboard with a refund-due flag; cash overpayment refused; partial payment cash-only; online payment after ready; ready reservations held 3 days with a 24 h reminder; customer cancellation until confirmed; roles Owner/Manager/Staff; staff MFA required in production with recovery codes. See `docs/decisions.md`.)
