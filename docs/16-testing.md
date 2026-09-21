# 16 — Testing

**Location:** `src/test/java/com/payflow/payflow/`

All tests are **integration tests** (`@SpringBootTest`, the full application context against a real PostgreSQL and Redis). There are no
unit tests with mocks, and no controller/HTTP-level tests (`MockMvc`). That's a deliberate fit for this project: the interesting bugs
(races, locks, transactions) only show up against a real database.

## 16.1 Infrastructure each test needs

| Test class | PostgreSQL | Redis | Kafka |
|---|---|---|---|
| `PayflowApplicationTests` | local (docker-compose) | local | local (the app starts the listener; startup survives without it, with connection warnings) |
| `ConcurrentTransferTest` | **Testcontainers** (throwaway `postgres:16`) | local | local* |
| `LockingStrategyComparisonTest` | **Testcontainers** | local | local* |
| `LedgerReconciliationTest` | local | local | local* |
| `TopUpBalanceConsistencyTest` | local | local | local* |
| `RateLimiterTest` | local (context startup) | local (+ fake servers it creates) | local* |

\* Every `@SpringBootTest` starts the whole app, including `OutboxPublisher` and the Kafka listener. Without Kafka, tests that go through
the optimistic path still pass (the outbox rows simply stay unpublished), but the logs fill with connection errors.

**In practice: run `docker compose up -d` before `./mvnw test`.** Tests on the local DB leave test users and wallets behind (emails are
randomised with UUIDs, so reruns don't collide).

## 16.2 What each test proves

### `ConcurrentTransferTest`: the pessimistic lock works
- A sender with exactly 1000; **50 threads** each send 100 via `transferService.transfer` (pessimistic), released together by a
  `CountDownLatch` "start line".
- Asserts: exactly **10 succeed**, **40 `InsufficientBalanceException`**, no other errors, the sender ends at **0** (never negative), the
  receiver at 1000, and the **ledger matches both stored balances**.
- Each thread uses a **unique idempotency key**. A shared key would turn 49 threads into replays and "prove" no-overdraft by accident
  (the comment in the test says exactly this).

### `LockingStrategyComparisonTest`: the benchmark
- The same 50-thread scenario, for **both** strategies. One warm-up round each, then 5 measured rounds, alternating which strategy goes
  first so neither always gets the warmer JVM.
- Counts optimistic retries by capturing the `DEBUG` log line `Optimistic conflict on attempt` (`OutputCaptureExtension`).
- Disables `show-sql`, because synchronised stdout would serialise both strategies and ruin the measurement.
- Correctness assertions run for **both** strategies: successes ≤ 10, every success wrote its own transaction row, never overdrawn,
  balances = start − moved, ledger matches. For pessimistic, it also asserts exactly 10/40/0 and zero conflicts.
- Prints the `COMPARE …` table used in the README (chapter 08).

### `LedgerReconciliationTest` (file: `LedgerReconcilationTest.java`, note the misspelling)
- Seeds 5 wallets with 1000 each, runs 100 random transfers, then asserts **conservation** (total before == total after, from the
  ledger) and **reconciliation** (no mismatches).
- ⚠️ **Weakness:** every transfer uses the same idempotency key `"123"`. After the first successful transfer, the other 99 are
  **replays that move no money**. On any later run against the same DB, the key already exists, so **zero** transfers execute. The
  test passes but checks almost nothing. Fix: `UUID.randomUUID().toString()` per transfer.
- The file name doesn't match the class name. It compiles only because the class isn't `public`. Rename the file.

### `TopUpBalanceConsistencyTest`: top-up and cache eviction
- Covered in chapter 11: cache warmed, top-up, key evicted, stored == derived == reconciled. Plus 5 × 13.37 = 66.85.

### `RateLimiterTest`: the token bucket
- Covered in chapter 10. Notable techniques:
  - **`MutableClock`**: a hand-advanced `Clock`, so refill is tested without `Thread.sleep`.
  - **A dead port** (6390) to simulate Redis down.
  - **A "black hole" `ServerSocket`** that accepts and never replies, to simulate a hung Redis and prove fail-open is **fast**.
  - **50 threads on one bucket** to prove the Lua script's atomicity.

### `PayflowApplicationTests`
- `contextLoads()`: the app starts, Flyway migrations apply, and Hibernate validation passes.

## 16.3 Testing techniques worth learning from this codebase

| Technique | Where | Why |
|---|---|---|
| `CountDownLatch` start line | concurrency tests | Releases all threads at once, to maximise contention |
| Unique idempotency key per thread | concurrency tests | Stops idempotency from masking a race |
| `isEqualByComparingTo` for `BigDecimal` | everywhere | Scale-independent comparison |
| Testcontainers + `@DynamicPropertySource` | concurrency tests | Clean, real PostgreSQL per test class |
| `TimeZone.setDefault(UTC)` in a static block | Testcontainers tests | Windows timezone-alias workaround (chapter 02) |
| Injectable `Clock` | `RateLimiter` | Deterministic time-based tests |
| Log capture to count events | benchmark | Measures retries without adding production metrics |

## 16.4 Gaps and suggestions

- **No coverage for** the optimistic path's correctness under contention (only in the benchmark), idempotent replay (same key twice),
  self-transfer, the outbox → Kafka → notification pipeline, JWT auth, controllers/HTTP status codes, or history pagination.
- **Shared Testcontainers setup.** Move the container into one `@TestConfiguration` with `@ServiceConnection`, so every test uses a
  throwaway Postgres (and a Redis/Kafka container) and nothing depends on docker-compose:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {
    @Bean @ServiceConnection PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:16"); }
    @Bean @ServiceConnection(name = "redis") GenericContainer<?> redis() { return new GenericContainer<>("redis:7.4").withExposedPorts(6379); }
    // + a KafkaContainer for the event pipeline
}
// @SpringBootTest @Import(TestcontainersConfig.class)
```

- **Kafka pipeline test idea:** do a transfer, then use Awaitility to wait until `notificationRepository.existsByTransactionId(txId)`
  is true. Publish the same payload twice and assert only one notification exists (proves the idempotent consumer).
