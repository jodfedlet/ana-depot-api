# ADR 0005 — Retry policy: exponential backoff, jitter, bounds
Status: accepted (implemented: `RetryPolicy`, `RetryExecutor`, used by the outbox dispatcher).

**Decision.** `RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, maxElapsed)`:
delay after failed attempt *n* is a random value in `[cap/2, cap]` with `cap = min(maxDelay, initial × multiplier^(n-1))` ("equal jitter": spreads clients, keeps a minimum wait).
A provider's `Retry-After` is a **lower bound** (capped at `maxDelay`). Retries are bounded by attempts **and** elapsed time. Only `TransientFailure` is retried; `PermanentFailure` and any other exception are not (validation/authorization errors must not be retried).
The outbox uses the same policy (`ana.outbox.*`), records attempts and `last_error`, dead-letters at the limit, and exposes counters. Tests assert exponential growth, the cap, jitter bounds/spread, Retry-After, attempt and elapsed bounds, and "no retry for permanent failures".
**Rules for callers:** a retried **write** must be idempotent first (ADR 0004); timeouts are always set explicitly on external calls (Hikari `connection-timeout` today).
**Circuit breaker:** none, deliberately — there is no unstable external dependency yet (no payment provider, no notification channel, no storage adapter). Add one (e.g. Resilience4j) around the first such client, with the same transient/permanent classification.
