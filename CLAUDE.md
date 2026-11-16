# ana-depot-api — Ana Dépôt backend

@AGENTS.md

Spring Boot 4 / Java 25 / Maven / PostgreSQL (Flyway + Spring Data JPA). **Modular monolith, minimal hexagonal architecture.**
Single-seller retail + wholesale store, launching in Haiti, selling in **HTG and USD**. Brand: Ana Dépôt (`ana-depot`). Sibling
project: `../ana-depot-web` (Next.js; still browser-only demo — not yet connected to this API).

Read `README.md` first, then `docs/architecture.md`, `docs/domain.md`, `docs/database.md`, `docs/api.md`, `docs/auth.md`, `docs/adr/`.

## Commands
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem   # the pom targets Java 25
mvn test            # unit + Testcontainers integration tests (needs Docker)
mvn package         # jar in target/
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
- Authorization = server-side `staff_members` row, never token claims. Deny by default (`SecurityConfig`).

## Testing
Integration tests extend `AbstractIntegrationTest` (one shared PostgreSQL container). Create uniquely named data; do not assert global
counts. JWT tests use locally generated keys — they are NOT Supabase integration tests (say so when reporting).

## Open business decisions — never invent defaults
Wholesale visibility; business-wide minimum order; final selling units; pack/box conversion; reservation hold period (`expires_at` stays null);
pickup details (the snapshot is opaque JSON); notification channel; who may record payments and partial-payment policy; customer cancellation
after ready; staff roles/permissions (membership is all-or-nothing); legal pages; low-stock policy; overpayment behavior.
