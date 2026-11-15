# Repository Instructions

## Objective

Make the smallest correct, production-quality change required by the task.

Prioritize:

1. Correctness
2. Security and data integrity
3. Simplicity
4. Maintainability
5. Consistency with the existing codebase
6. Performance when relevant

Existing repository conventions take precedence over generic preferences unless they are clearly unsafe or incorrect.

Avoid overengineering.

---

## Persistence

Persistence must use Spring Data JPA / JPA / Hibernate. Do not use JdbcTemplate, JdbcClient, NamedParameterJdbcTemplate, direct JDBC, ResultSet mapping, or raw SQL execution in application code. Prefer entity relationships, JPQL/HQL, typed projections, Specifications, Criteria API, and repository abstractions. Native SQL is an exceptional last resort requiring a documented technical justification.

Narrow infrastructure exceptions (for example PostgreSQL advisory locks, ShedLock, or `FOR UPDATE SKIP LOCKED` retention deletes) must live outside ordinary application persistence paths and remain explicitly documented. Flyway migrations are not application persistence and are not rewritten as JPA.

---

## Working Method

Before editing:

1. Locate the relevant entry point.
2. Trace the directly affected code path.
3. Inspect the closest relevant tests.
4. Identify the root cause or correct extension point.
5. Search for an existing repository pattern before creating something new.

Use targeted searches.

Do not recursively inspect the repository for a localized task.

Stop investigating once enough evidence exists to implement safely.

---

## Scope

Make only changes required by the task and directly affected invariants.

Do not perform unrelated:

- refactoring
- renaming
- formatting
- dependency upgrades
- package restructuring
- architecture changes
- cleanup

If an unrelated issue is discovered, report it instead of expanding scope unless it directly blocks the requested change.

---

## Implementation

Prefer:

- clear code
- explicit behavior
- cohesive responsibilities
- existing abstractions
- existing patterns
- straightforward control flow

Avoid:

- speculative abstractions
- unnecessary interfaces
- premature optimization
- hidden behavior
- unnecessary dependencies
- unrelated rewrites

Fix root causes rather than suppressing downstream symptoms.

Do not weaken validation, security, typing, tests, or error handling merely to make the implementation pass.

---

## Context and Token Efficiency

Use the minimum repository context necessary to complete the task safely.

Prefer:

- targeted symbol searches
- targeted filename searches
- reading relevant portions of large files
- direct callers and consumers
- relevant tests
- reusing information already discovered

Avoid:

- repository-wide scans without a concrete reason
- reopening unchanged files
- reading generated/build output
- inspecting unrelated tests
- repeatedly searching for information already established
- verbose planning for straightforward tasks

For broad tasks:

1. search globally;
2. classify matches;
3. inspect representative implementations;
4. expand only where behavior materially differs.

---

## Validation

Run the narrowest relevant validation first.

Typical order:

1. affected test
2. affected package/module tests
3. compile/static validation
4. broader tests when warranted
5. full verification when required

Do not repeatedly run expensive validation when relevant code has not changed.

Do not fix unrelated warnings or test failures.

Use repository-provided scripts and commands.

---

## Security and Data Integrity

Preserve:

- authentication
- authorization
- tenant/data isolation where applicable
- validation
- transaction integrity
- sensitive-data protection

Never:

- hardcode secrets
- expose credentials or tokens
- trust client-side authorization
- bypass authorization for convenience
- suppress integrity failures
- log sensitive data

---

## Database Migrations

Released Flyway versioned migrations are immutable.

Never edit, rename, delete, renumber, replace, repurpose, reformat, or merge an already protected `V<number>__*.sql`.

For schema changes:

1. inspect existing migration versions;
2. create the next valid migration;
3. use a new corrective migration for already-released schema;
4. preserve existing production data;
5. run repository migration validation.

Never use `flyway repair`, validation disabling, checksum manipulation, or automatic history-table modification to hide migration problems.

---

## Output

Keep completion responses concise.

Report:

- what changed;
- root cause when relevant;
- validation performed;
- remaining blocker only if one exists.

Do not provide a long walkthrough unless requested.

---

## Regression Safety and Protected Behavior

The test suite is not the sole source of truth for product behavior. Tests must validate established business requirements and invariants; they must not redefine them merely to match a changed implementation.

### Protected Regression Tests

Critical business behavior must be covered by protected regression tests.

Protected tests include, but are not limited to:

- authentication and authorization;
- tenant/data isolation;
- permissions and role assignment;
- product creation and management;
- employee creation and management;
- inventory and stock behavior;
- POS behavior and POS-specific pricing;
- billing and subscription behavior;
- entitlements and feature gates;
- currencies and financial calculations;
- accounting and ledger behavior;
- critical checkout/payment flows.

Do not modify a protected regression test merely because the current implementation no longer satisfies it.

When a protected regression test fails after an implementation change:

1. Treat the failure as evidence that the implementation may be incorrect.
2. Investigate the requirement and existing behavior.
3. Fix the implementation first when the requirement has not intentionally changed.
4. Do not weaken, remove, or rewrite the assertion simply to make the test pass.
5. If the intended business behavior has genuinely changed, explicitly identify that behavioral change and obtain human approval before modifying the protected regression test.

### Agent Test Modification Rule

An agent may add tests required by a task.

An agent must not silently change established expected behavior by modifying existing regression tests.

In particular, never:

- change an expected value solely because the implementation now returns that value;
- remove an assertion because it prevents the new implementation from passing;
- weaken authorization or validation assertions;
- replace a specific business assertion with a generic success assertion;
- delete a regression test because the underlying behavior is inconvenient;
- update an E2E test to encode an accidental UI or API behavior as the new requirement.

When implementation and an existing regression test disagree, assume the implementation is wrong until the intended behavior is explicitly established otherwise.

### Business Invariants

For behavior that must remain stable across implementations, preserve explicit business invariants.

Examples:

- POS pricing is independent from Revofin ERP subscription pricing.
- Authorization is determined by server-side permissions, not UI visibility.
- Users without an explicitly granted permission must not receive that permission implicitly.
- Employee salary currency must be explicitly supported where business and employee currencies can differ.
- Creating a product must not require unrelated inventory state unless explicitly required by the product domain.
- Changing one product/module's pricing must not silently alter another product/module's pricing.

Tests should assert these invariants rather than implementation details whenever practical.

### Requirement Before Test Changes

Before changing an existing regression test, identify:

1. the business requirement or invariant being changed;
2. why the existing behavior is no longer correct;
3. which application behavior is intentionally changing;
4. which other workflows may be affected.

Do not infer a requirement change from a failing test alone.

### Regression Preservation

Every confirmed production regression must result in a regression test when automation is reasonably possible.

The regression test must reproduce the original incorrect behavior and verify the intended behavior.

The test must remain part of the regression suite after the bug is fixed.

### Impact Analysis

For changes affecting shared behavior, identify directly dependent workflows before modifying tests or implementation.

Examples include:

- pricing → billing, subscriptions, entitlements, POS;
- permissions → roles, users, business settings, protected APIs;
- currency → payments, accounting, salaries, reports;
- product → inventory, purchasing, POS, sales;
- authentication → workspace access, API authorization, frontend routing.

Do not assume that a test passing in the directly modified module proves that dependent workflows remain correct.

### Protected Test Ownership

Protected regression tests should be treated as higher-trust artifacts than agent-generated tests.

When repository controls support it, changes to protected regression tests should require explicit human review.

The preferred repository structure is:

    tests/
      regression/
        protected/

The exact location may follow existing repository conventions.

### Test Updates Must Preserve Intent

When implementation details change without changing business behavior:

- update tests only as necessary to follow the implementation boundary;
- preserve assertions about business outcomes;
- avoid replacing meaningful assertions with weaker assertions;
- do not make tests pass by removing the behavior they were designed to protect.

---
