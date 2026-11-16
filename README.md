# ana-depot-api

Backend of **Ana Dépôt** (`ana-depot`): a single-seller retail and wholesale store launching in Haiti, selling in **HTG and USD**.
Spring Boot 4 · Java 25 · Maven · PostgreSQL · Flyway · Spring Data JPA. Sibling project: [`../ana-depot-web`](../ana-depot-web)
(Next.js storefront + administration; currently a browser-only demo, **not yet connected** to this API).

> **Status: foundation phase.** The domain, schema, core use cases, security boundary and reliability infrastructure are implemented
> and tested. Only a small REST surface exists (public catalog reads and `/me`). See [Implemented vs designed](#implemented-vs-designed).

## Identity
Brand **Ana Dépôt**, technical id `ana-depot`. Maven `com.anadepot:ana-depot-api`, Java root package `com.anadepot`.
Project layout and the identity cleanup of the original generated skeleton: [docs/architecture.md](docs/architecture.md#project-history).

## Run it locally
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem
export DB_PASSWORD=<choose>                      # compose.yaml refuses to start without it
docker compose up -d postgres
cp .env.example .env                             # placeholders only; export them in your shell (nothing reads .env automatically)
DB_URL=jdbc:postgresql://localhost:5432/ana_depot DB_USER=ana_depot DB_PASSWORD=$DB_PASSWORD mvn spring-boot:run
curl localhost:8080/actuator/health
curl localhost:8080/api/v1/products
```
Flyway creates the schema on startup (`src/main/resources/db/migration`). Hibernate only **validates** that entities match it.

### Configuration (environment)
| Variable | Meaning |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL. No default password. |
| `SUPABASE_JWT_ISSUER` | `https://<project-ref>.supabase.co/auth/v1`. **Empty = every token is rejected** (fail closed). |
| `SUPABASE_JWT_AUDIENCE` | default `authenticated` |
| `ANA_BOOTSTRAP_ADMIN_SUBJECT` | Supabase user id (UUID) of the first store administrator; one-time ([docs/auth.md](docs/auth.md)) |
| `ana.*` (application.yaml) | idempotency retention, outbox batch/backoff/attempts, rate limits per operation, trusted proxies, upload TTL/size/types |

## Tests
```bash
mvn test        # 175 tests: unit (pure Java) + Testcontainers/PostgreSQL integration; needs Docker
```
Highlights: money & per-currency pricing; shared stock; mixed-currency reservations and snapshots; per-currency payments, ledger reversal/adjustment/
reconciliation; real concurrent transactions (no overselling, duplicate payments); idempotency; outbox delivery/retry/dead-letter/crash recovery/ordering;
deduplication; rate limiting across instances; presigned-upload verification; database constraints attacked directly; JWT validation; architecture guards.
**JWT tests use locally generated keys; no test talks to a real Supabase project** ([docs/auth.md](docs/auth.md#testing-honesty)).

## Documentation
| | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | packages, dependency rules, bounded contexts, diagrams |
| [docs/domain.md](docs/domain.md) | money, per-currency pricing, inventory, reservations, payments/ledger; mapping to the storefront |
| [docs/database.md](docs/database.md) | schema, relationships, constraints (DB vs application), migration conventions |
| [docs/api.md](docs/api.md) | resources, DTOs, errors, pagination, idempotency, concurrency |
| [docs/auth.md](docs/auth.md) | Supabase Auth boundary, JWT validation, staff authorization, provisioning, environment |
| [docs/adr/](docs/adr/) | architecture decision records (ledger, outbox, idempotency, retry, dedup, uploads, rate limiting, concurrency, observability, pricing, auth) |

## Implemented vs designed
| Area | Implemented & tested | Designed / documented only |
|---|---|---|
| Money, currencies | `Money`, `CurrencyTotals`, HTG/USD, cross-currency rejection | conversion policy (deliberately none) |
| Catalog | categories + attributes, products, variants, per-currency prices/tiers, SKU rules, price-change audit; **REST: public GET products** | admin REST (create/edit/activate), category admin, search/filter endpoints |
| Inventory | shared stock position, adjust (idempotent, audited), movements, row-lock reservation | REST endpoints, low-stock reporting |
| Reservations | submit (server-side pricing, stock hold), accept/reject/ready/collect/cancel/expire, snapshots, per-currency totals, history | REST endpoints, guest tracking by reference+phone, customer-facing notes view |
| Payments | record / reverse / adjust, per-currency settlement, ledger, reconciliation | REST endpoints, payment provider integrations (none) |
| Reliability | idempotency, outbox + dispatcher, dedup, retry policy, rate limiter, audit trail, correlation ids, JSON logs, metrics | circuit breaker (no external dependency yet), Prometheus registry, dedicated management port |
| Security | Supabase JWT validation, staff membership table, deny-by-default, `/me`, bootstrap admin | roles/permissions (undecided), admin REST |
| Uploads | presigned-upload lifecycle (core + persistence) with an in-memory test double | a real storage adapter (Supabase Storage/S3) — default storage **fails closed** |
| Notifications | outbox events + a logging consumer | SMS/e-mail/WhatsApp (channel undecided) |

## Known limitations and open decisions
See [docs/domain.md](docs/domain.md#open-business-decisions) and each ADR's *Consequences*. Notable: overpayment is accepted (existing storefront
behavior, undecided); no hold period (`expires_at` null); reversal/adjustment allowed only while a reservation is READY_FOR_PICKUP (conservative,
undecided); roles/permissions not modelled; unit list and pack/box conversion undecided; variant option axes must be category attributes.
