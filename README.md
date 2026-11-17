# ana-depot-api

Backend of **Ana Dépôt** (`ana-depot`): a single-seller retail and wholesale store launching in Haiti, selling in **HTG and USD**.
Spring Boot 4 · Java 25 · Maven · PostgreSQL · Flyway · Spring Data JPA. Sibling project: [`../ana-depot-web`](../ana-depot-web)
(Next.js storefront + administration; being connected to this API, see [docs/integration-map.md](docs/integration-map.md)).

> **Status: Phase 2 admin API complete (backend side).** Public catalog and PostgreSQL full-text search, pickup settings, guest reservations and lookup, customer accounts, staff product images on
> Cloudflare R2, Supabase Auth verification and Supabase PostgreSQL configuration, **and the complete staff API** (categories, products, inventory, reservations, payments, customers, reports) are implemented and tested.
> The web admin is **not** wired to it yet (that is the frontend's work, see [docs/integration-map.md](docs/integration-map.md)). See [Implemented vs designed](#implemented-vs-designed) and [docs/api.md](docs/api.md) (exactly what exists).

## Identity
Brand **Ana Dépôt**, technical id `ana-depot`. Maven `com.anadepot:ana-depot-api`, Java root package `com.anadepot`.
Project layout and the identity cleanup of the original generated skeleton: [docs/architecture.md](docs/architecture.md#project-history).

## Run it locally
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem
cp .env.example .env                              # placeholders only; Spring does not read .env: load it into your shell
$EDITOR .env                                      # at least SPRING_DATASOURCE_PASSWORD
set -a; source .env; set +a
docker compose up -d postgres                     # compose.yaml refuses to start without SPRING_DATASOURCE_PASSWORD
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run  # `local` supplies jdbc:postgresql://localhost:5432/ana_depot and the user ana_depot
curl localhost:8080/actuator/health
curl localhost:8080/api/v1/products
```
Flyway creates the schema on startup (`src/main/resources/db/migration`). Hibernate only **validates** that entities match it.

### Profiles
| Profile | Purpose |
|---|---|
| (none) | base `application.yaml`: no datasource defaults, so nothing starts against an unintended database |
| `local` | development: localhost database, small pool, readable logs |
| `prod` | production: explicit Hikari limits, JSON logs, **refuses to start** when the database URL/user/password or the token issuer is missing |
| `test` | used by the test suite (Testcontainers supply the datasource) |

### Configuration (environment)
| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL (Supabase in production, see [docs/supabase-database.md](docs/supabase-database.md)). No defaults except profile `local`. |
| `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` | optional separate (direct/session) connection for migrations; required when the application uses the transaction pooler |
| `SUPABASE_URL` | project URL; the token issuer defaults to `<SUPABASE_URL>/auth/v1` |
| `SUPABASE_JWT_ISSUER` | `https://<project-ref>.supabase.co/auth/v1`. With `SUPABASE_URL` also empty, **every token is rejected** (fail closed). |
| `SUPABASE_JWKS_URL` | optional override of `<issuer>/.well-known/jwks.json` (private network, proxy, local stack) |
| `SUPABASE_JWT_AUDIENCE` | default `authenticated` |
| `ANA_BOOTSTRAP_ADMIN_SUBJECT` | Supabase user id (UUID) of the first store administrator; one-time ([docs/auth.md](docs/auth.md)) |
| `ana.*` (application.yaml) | idempotency retention, outbox batch/backoff/attempts, rate limits per operation, trusted proxies, upload TTL/size/types |

## Tests
```bash
mvn test        # 380 tests (7 skipped: 6 real-R2 opt-in + 1 search performance): unit (pure Java) + Testcontainers PostgreSQL + MinIO integration; needs Docker
mvn package     # jar in target/
```
Highlights: money & per-currency pricing; shared stock; mixed-currency reservations over HTTP; real concurrent submissions for the last units across HTG and USD (no overselling); idempotent replay and key reuse;
enumeration-resistant lookup; customer-view data-leak checks; per-currency payments and ledger; outbox, deduplication, rate limiting across instances; PostgreSQL full-text search (21 + plan + opt-in 20 000-product performance test);
presigned uploads against MinIO; Data API lockdown (RLS + revoked roles, simulated Supabase roles on plain PostgreSQL); production-configuration guard; architecture guards; staff API over HTTP for every module (401/403 matrix, validation, audit, idempotent retries, optimistic-version conflicts and simultaneous requests: exactly one winner).
**Honest limits:** JWT tests use locally generated keys (no test talks to a real Supabase project, [docs/auth.md](docs/auth.md#testing-honesty)); storage tests use **MinIO, an S3-compatible server, not Cloudflare R2**; the real-R2 test
(`R2_TEST_ENABLED=true mvn test -Dtest=R2RealIntegrationTest`, [docs/storage-r2.md](docs/storage-r2.md)) and the hosted-Supabase checks ([docs/supabase-database.md](docs/supabase-database.md)) are **pending**; the performance test is opt-in (`-Dsearch.perf=true`).

## Documentation
| | |
|---|---|
| [docs/api.md](docs/api.md) | **the implemented contract**: endpoints, DTOs with JSON examples, errors, headers, idempotency, rate limits |
| [docs/integration-map.md](docs/integration-map.md) | storefront screens mapped to API calls, gaps, progress notes |
| [docs/architecture.md](docs/architecture.md) | packages, dependency rules, bounded contexts, diagrams |
| [docs/domain.md](docs/domain.md) | money, per-currency pricing, inventory, reservations, payments/ledger; mapping to the storefront |
| [docs/database.md](docs/database.md) | schema, migrations V1-V13, constraints (DB vs application), conventions |
| [docs/search.md](docs/search.md) | PostgreSQL full-text search: design, Creole/French limits, measured performance |
| [docs/auth.md](docs/auth.md) | Supabase Auth boundary, JWT validation, staff authorization, provisioning, environment |
| [docs/supabase-database.md](docs/supabase-database.md) | Supabase PostgreSQL: connection mode, TLS, pool, role, Data API lockdown, verification |
| [docs/storage-r2.md](docs/storage-r2.md) | Cloudflare R2 images: bucket, token, CORS, public domain, tests, checklist |
| [docs/local-supabase.md](docs/local-supabase.md) | local Supabase stack for development (written by the authentication work) |
| [docs/adr/](docs/adr/) | decisions: ledger, outbox, delivery, idempotency, retry, dedup, uploads, rate limiting, concurrency, observability, pricing, auth, Supabase Postgres/lockdown (0013), R2 (0014), full-text search (0015) |

## Implemented vs designed
| Area | Implemented & tested | Designed / not exposed yet |
|---|---|---|
| Money, currencies | `Money`, `CurrencyTotals`, HTG/USD, cross-currency rejection | conversion (deliberately none) |
| Catalog | categories + attributes, products, variants, per-currency prices/tiers, SKU rules, price-change audit; **REST: public categories, products, search with filters/availability; staff categories (attribute-safe edits, reorder, move products) and products (per-currency prices, variants, lifecycle, FTS + SKU listing)** | featured rank / "new" flag (none in the model) |
| Search | PostgreSQL full-text (GIN), accents/plurals/prefix, ranking, filters, reindex endpoint | typo tolerance, synonyms, Creole variant table (needs real vocabulary) |
| Inventory | shared stock position, adjust (idempotent, audited), movements, row-lock reservation; **staff REST: positions, register, adjust, threshold, movements** | low-stock *policy* (the seller sets thresholds; none is invented), stock valuation (no cost data) |
| Pickup | settings model + `GET /store/pickup`, staff `GET/PUT /admin/settings/pickup`, snapshot on each reservation | pickup windows/slots, hold period (undecided) |
| Reservations | **REST: guest submit + lookup**, customer-safe view, account list/detail; terms acknowledgement stored (version + time); **staff REST: list/search/filter, detail, accept/reject/ready/collect/cancel, notes** | customer cancellation (policy undecided), expiry job (no hold period), notifications |
| Customers & reports | **staff REST: customers (search, per-currency summary, guest correction), reports (overview, sales, payments, products, reservations, stock) - every figure per currency** | roles/permissions for finance data (undecided) |
| Accounts | `/me`, `/me/profile`, `/me/reservations`; registered customers separate from guests (V9) | verified-claim process to attach past guest reservations to an account |
| Payments | record / reverse / adjust, per-currency settlement, ledger, reconciliation; **staff REST** incl. payment list, ledger, reconciliation | payment providers (none), over-payment and partial-payment policy (undecided) |
| Reliability | idempotency, outbox + dispatcher, dedup, retry policy, rate limiter, audit trail, correlation ids, JSON logs, metrics | circuit breaker, Prometheus registry, dedicated management port |
| Security | Supabase JWT validation (JWKS, optional override), staff membership table, deny-by-default, bootstrap admin | roles/permissions (undecided) |
| Uploads | `R2ObjectStorage` (Cloudflare R2, S3 API) behind the `ObjectStorage` port; staff image endpoints; scheduled cleanup; tested against MinIO (**not** R2) | real-R2 verification **pending**; Supabase Storage is deliberately not used |
| Database | Flyway V1-V13, `ddl-auto=validate`, Data API lockdown (RLS + revoked API roles), profiles local/prod | hosted-Supabase verification (manual checklist) |
| Notifications | outbox events + a logging consumer | SMS/e-mail/WhatsApp (channel undecided) |

## Known limitations and open decisions
See [docs/domain.md](docs/domain.md#open-business-decisions) and each ADR's *Consequences*. Notable: overpayment is accepted (existing storefront
behavior, undecided); no hold period (`expires_at` null); reversal/adjustment allowed only while a reservation is READY_FOR_PICKUP (conservative,
undecided); roles/permissions not modelled; unit list and pack/box conversion undecided; variant option axes must be category attributes.
