# 17 — Known Issues & Improvement Roadmap

Each item below was **verified against the current code** (2026-09-21, including uncommitted changes). Items that were in the earlier
`PROJECT_ANALYSIS.md` are marked with their old number (`P#`), so you can track them.

Severity: 🔴 High (money or security impact) · 🟠 Medium (wrong behaviour or bad API) · 🟢 Low (polish/performance).

## 17.1 Summary table

| # | Sev | Issue | Area | Was |
|---|---|---|---|---|
| 1 | 🔴 | Amounts with > 4 decimals are rounded by Postgres, and the ledger drifts from the balance | Money | P1 (still open) |
| 2 | 🔴 | Idempotency keys are global: another user's key replays *their* transaction to you | Idempotency / security | P3 (still open) |
| 3 | 🔴 | Concurrent duplicate + low balance → client told "insufficient" for a payment that succeeded | Idempotency | P2 (still open) |
| 4 | 🔴 | JWT secret has a working default committed to git | Security | P7 (still open) |
| 5 | 🟠 | Pessimistic executor doesn't write outbox events (code drift) | Events | new |
| 6 | 🟠 | Catch-all handler logs nothing; client errors (missing header, bad JSON) become 500 | Errors | P5 (still open) |
| 7 | 🟠 | Any integrity violation during a transfer is treated as "duplicate key" → 500 | Idempotency | P4 (still open) |
| 8 | 🟠 | Top-up: concurrent writes → 500; not idempotent | Wallet | P6 (still open) |
| 9 | 🟠 | `LedgerReconciliationTest` reuses key `"123"` and effectively tests nothing | Tests | partially fixed (topUp id and emails fixed) |
| 10 | 🟠 | Unauthenticated requests get 403 with no body instead of 401 | Security/API | P9 |
| 11 | 🟠 | Rate-limit token is spent on idempotent replays | Rate limit | new |
| 12 | 🟠 | Cache eviction failure after commit surfaces as 500 for a committed transfer | Cache | new |
| 13 | 🟢 | Missing indexes: `transactions(from_wallet_id)`, `(to_wallet_id)`, `outbox_events(is_published)` | Performance | P8 (partly: ledger index added in V7) |
| 14 | 🟢 | Outbox rows never cleaned up; multi-instance publishers duplicate work | Events | new |
| 15 | 🟢 | No dead-letter topic; poison Kafka messages are skipped after 10 attempts | Events | new |
| 16 | 🟢 | History has no default sort; unknown sort → 500; returns raw `Page` | API | new |
| 17 | 🟢 | Extra SELECT before every insert (assigned UUIDs + `merge`) | Performance | new |
| 18 | 🟢 | Smaller items: validation errors lack field names, `/health` checks nothing, no password policy, case-sensitive emails, top-up can mint money | Polish | P9/P10 |

## 17.2 Details and fixes

### 1 🔴 Decimal rounding breaks the ledger
**What:** `NUMERIC(19,4)` rounds anything past 4 decimals, and nothing limits decimals on input. Java computes balances on the
*unrounded* value, and each value is rounded independently when stored.

**Example:** the sender has `100.0000` and sends `0.00005`. Both ledger rows store `0.0001`. The sender's balance `99.99995` is stored as
`100.0000`, and the receiver gets `0.0001`. Money is created from nothing, and reconciliation reports a mismatch. `0.00004` rounds to `0` →
`CHECK (amount > 0)` fails → issue 7 → 500.

**Fix:** reject extra precision at the edge:
```java
public record TransferRequest(@NotNull UUID toWalletId,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount) {}
public record TopUpRequest(@NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount) {}
```

### 2 🔴 Global idempotency keys
**What:** `UNIQUE(idempotency_key)` spans all users, and `findByIdempotencyKey` doesn't filter by sender. User B sending a key A
already used gets `200 COMPLETED` with **A's** transaction id and amount, while B's money doesn't move.

**Fix:** a V10 migration: drop `uk_transactions_idempotency_key` and add `UNIQUE (from_wallet_id, idempotency_key)`. Look up with
`findByFromWalletIdAndIdempotencyKey`. Optionally store a request hash and return `422` when the same key arrives with a different body.

### 3 🔴 Duplicate request misreported as insufficient balance
**What:** two identical requests race; A commits; B reads the new, lower balance and throws `InsufficientBalanceException`
before reaching the INSERT that would trigger the replay.

**Fix:** re-check the key *after* locking (pessimistic), or at the start of each attempt before the balance check (optimistic):
```java
Wallet first = walletRepository.findByIdForUpdate(firstId)...; Wallet second = ...;
Optional<Transaction> already = transactionRepository.findByIdempotencyKey(key);
if (already.isPresent()) return toResponse(already.get());
```

### 4 🔴 JWT secret default in git
**What:** `secret: ${JWT_SECRET:ixpX…}`. Any deployment without the env var uses a key that anyone with repo access can use to forge a
token for any user id.

**Fix:** remove the default (`${JWT_SECRET}`) so startup fails if it's unset. Keep a dev value in an untracked
`application-local.yaml`. Rotate the committed key.

### 5 🟠 Code drift between executors
**What:** only `OptimisticTransferExecutor` writes the `OutboxEvent`. `DECISIONS.md` (Day 19) predicted exactly this.

**Fix, one of:**
- Pick one strategy for production and delete the other (the benchmark favours **pessimistic** for hot wallets).
- Or extract the shared steps into a component both executors call after loading their wallets:
  ```java
  TransferResponse applyTransfer(Wallet sender, Wallet receiver, BigDecimal amount, String key)   // txn row, ledger, balances, outbox, evict
  ```
Then update `DECISIONS.md`.

### 6 🟠 Error handling gaps
**Fix:** extend `ResponseEntityExceptionHandler` (maps Spring MVC exceptions to 4xx), add explicit handlers for
`MissingRequestHeaderException` → 400 and `ObjectOptimisticLockingFailureException` → 409, include field errors in `INVALID_DATA`, and
add `log.error("Unhandled exception", ex)` to the catch-all.

### 7 🟠 Every `DataIntegrityViolationException` treated as a duplicate key
**Fix:** check which constraint failed (`uk_transactions_idempotency_key`) via the root `ConstraintViolationException`. Rethrow anything else
so it maps to a proper error. Issue 1's fix also removes the most likely trigger.

### 8 🟠 Top-up robustness
**Fix:** use `findByIdForUpdate` (or retry on `ObjectOptimisticLockingFailureException`), and add an `Idempotency-Key` to top-up (store it on the
ledger entry, or add a `top_ups` table).

### 9 🟠 `LedgerReconciliationTest`
**Fix:** a unique key per transfer, rename the file to `LedgerReconciliationTest.java`, move it to Testcontainers.

### 10 🟠 401 vs 403
**Fix:** in `SecurityConfig`,
```java
.exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
    res.setStatus(401); res.setContentType("application/json");
    res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid token\"}");
}))
```

### 11 🟠 Replays spend rate-limit tokens
**Options:** check idempotency (a read) before the rate limiter, or accept it and document that retries count. Charging replays is a defensible
anti-abuse choice. Decide it deliberately and record it in `DECISIONS.md`.

### 12 🟠 Post-commit cache eviction can fail the request
**Fix:** wrap the `afterCommit` delete in try/catch + WARN (the TTL bounds staleness anyway), mirroring the rate limiter's fail-open philosophy.

### 13 🟢 Indexes
```sql
-- V10__add_missing_indexes.sql
CREATE INDEX idx_transactions_from_wallet ON transactions (from_wallet_id, created_at DESC);
CREATE INDEX idx_transactions_to_wallet   ON transactions (to_wallet_id,   created_at DESC);
CREATE INDEX idx_outbox_unpublished       ON outbox_events (created_at) WHERE is_published = false;   -- partial index
```

### 14 🟢 Outbox housekeeping and scaling
Delete (or archive) published rows older than N days in a scheduled job. For multiple instances, claim rows with
`SELECT … FOR UPDATE SKIP LOCKED` inside a transaction, so each row is published by one instance.

### 15 🟢 Dead-letter topic
Configure `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` and a backoff, so poison messages go to `transaction-events.DLT`
instead of being dropped.

### 16 🟢 History API polish
`@PageableDefault(size = 20, sort = "createdAt", direction = DESC)`, whitelist sortable fields, return a stable DTO / `PagedModel`.

### 17 🟢 Extra SELECT on insert
Implement `Persistable<UUID>` with an `isNew` flag (set in the constructor, cleared by `@PostPersist`/`@PostLoad`) on `User`,
`Transaction`, `LedgerEntry`, `OutboxEvent`, `Notification`, `ProcessedEvent`.

### 18 🟢 Smaller items
- `/health` → use Spring Boot Actuator (`/actuator/health` checks the DB, Redis and Kafka), which also enables the `ratelimit.failopen`
  metric wished for in `DECISIONS.md`.
- Normalise emails to lower case on register and login.
- Password minimum length (`@Size(min = 8)`).
- Guard top-up behind an admin role or a payment-provider webhook.
- Remove the redundant `walletRepository.save(wallet)` in `topUp`, unused imports (`TransferService`, `UserController` comments,
  `OutboxEventRepository` imports `Optional`), and leftover template comments (`JwtService`, `HealthController`).

## 17.3 Suggested roadmap (in order)

1. **Money-safety fixes:** #1 (decimals), #2 (per-user keys), #3 (re-check after lock), #4 (secret). Each has a small, testable fix.
2. **Choose one transfer strategy** (#5) and remove the duplication. Record the decision in `DECISIONS.md`.
3. **API hygiene:** #6, #7, #10, #16. Add MockMvc tests for every status code in chapter 15.
4. **Test infrastructure:** shared Testcontainers config (Postgres + Redis + Kafka), fix #9, add a Kafka pipeline test.
5. **Operability:** Actuator, metrics (fail-open counter, outbox lag = count of unpublished rows), structured logging, a nightly
   reconciliation job with alerting.
6. **Performance:** #13, #17, keyset pagination.
7. **Features:** look up a wallet by email, notifications per user (sender and receiver), multi-currency, withdrawals (which
   **must** use fail-*closed* limits, see chapter 10).
