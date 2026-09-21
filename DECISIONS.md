# Decisions

Tradeoffs we chose on purpose, and what it would take to revisit them.

## 2026-09-13 (Day 19): Duplicated transfer logic between the two executors

**Decision:** Accepted duplication between the two executors deliberately, to keep both strategies side-by-side for the interview comparison.

- `TransferExecutor` (pessimistic: `SELECT ... FOR UPDATE`) and `OptimisticTransferExecutor` (`@Version` check at commit)
  differ only in how they load the two wallets. The transaction row, the ledger entries and the balance updates are copied.
- **Cost:** any change to the transfer steps (a new ledger field, a new status, a fee) has to be made in both classes, or they drift apart.
- **Revisit when:** one strategy is chosen for production, or the shared steps change for the first time. Then either delete the
  losing executor, or extract the shared steps into one method that both executors call after loading their wallets.

## 2026-09-19 (Day 21): `StringRedisTemplate` for the rate limiter, not `RedisTemplate`

**Decision:** `RateLimiter` talks to Redis through `StringRedisTemplate`.

- Redis passes every script argument as text, and `token_bucket.lua` `tonumber()`s them on the other side. `StringRedisTemplate`
  is the string-specialised template, so what Java sends is what Lua reads -- no serializer in the middle to surprise us.
- **Rejected:** generic `RedisTemplate` with custom serializers. More configuration, and a JDK-serialized key would no longer
  match the plain `rate_limit:transfer:<id>` keys the script and `redis-cli` both expect. No benefit at this scale.
- **Cost:** every argument is stringified at the call site (`String.valueOf(...)`), and the caller parses the reply back.
- **Revisit when:** we start storing structured values (JSON bucket state, per-tier configs) in the same keyspace.

## 2026-09-19 (Day 21): `RateLimitResult` record instead of a bare `boolean`

**Decision:** `RateLimiter.check(...)` returns `RateLimitResult(boolean allowed, double tokensLeft)`.

- The endpoint needs more than allow/deny: a 429 should carry **Retry-After**, and computing that needs to know how far the
  bucket is from the next whole token. `tokensLeft` is fractional for exactly that reason -- `0.4` means the next token is
  `0.6 / refillPerSecond` seconds away.
- Also keeps raw `List` replies from Redis out of the call sites (the same habit as not passing raw maps around money code):
  the `(Long)` / `(String)` cast and the `Double.parseDouble` live in one place.
- **Cost:** one extra type for two values.
- **Revisit when:** Step 4 settles what Retry-After actually needs. If it wants seconds, the record is where that belongs --
  as a derived accessor, computed from `tokensLeft` and the refill rate, not recomputed in the controller.

## 2026-09-19 (Day 21): The stored `wallets.balance` column is the source of truth; the ledger is the audit trail

**Decision:** `wallets.balance` is authoritative for every decision that moves money. The ledger is the independent
record we reconcile it against, not a second live balance.

- **What reads what today:** `GET /api/wallets/me` returns `wallet.getBalance()` (stored). Both executors validate
  `senderWallet.getBalance()` (stored) under a row lock or a `@Version` check. `WalletService.getDerivedBalance(...)`
  -- the ledger sum, cached in Redis for 5s -- **has no callers at all** outside tests. So nothing authorises a
  transfer against the derived value, and the cache cannot influence a money decision.
- **Why stored, not derived:** the overdraft check has to be serialised against concurrent transfers. A locked row gives
  us that (`SELECT ... FOR UPDATE`); a `SUM` over ledger entries does not, and a *cached* sum certainly does not.
  Deriving the authoritative balance would mean locking every ledger row for the wallet, or accepting a TOCTOU race on
  the very check that prevents overdraft.
- **Why keep the ledger then:** it is append-only, so it is the thing an auditor can recompute independently.
  `ReconciliationService` compares the two and any drift is a bug in a write path, detectable after the fact.
- **The invariant every write path owes:** append the ledger entries AND update the stored column, in the same
  transaction. `topUp` does both (CREDIT entry + `setBalance`); both executors do both (DEBIT+CREDIT + both balances).
  Verified by `TopUpBalanceConsistencyTest` and `LedgerReconciliationTest` -- reconciliation reports zero mismatches.
- **Known, bounded:** `getDerivedBalance` is a read-through cache, so it has the usual race -- a reader that computes
  the sum just before a transfer commits can write that pre-commit value into Redis just after the post-commit evict,
  leaving it stale until the 5s TTL expires. Acceptable only because this value is display-only. **If the wallet endpoint
  is ever switched to serve it, that race has to be closed first** (or the endpoint reads the stored column and the cache
  keys off it instead).
- **Revisit when:** the derived balance gains a real caller, or the ledger grows enough that reconciliation stops being
  cheap to run over all wallets.

## 2026-09-19 (Day 21): When Redis is down, the rate limiter FAILS OPEN

**Decision:** if Redis cannot answer, `RateLimiter.check(...)` logs a warning and allows the request. A rate limiter
outage must never stop a customer moving their own money.

**Why open and not closed.** The limiter protects *capacity*, not *correctness*. Nothing about the money invariant
depends on it: the overdraft check is enforced by the locked wallet row (`TransferExecutor`) or the `@Version` check
(`OptimisticTransferExecutor`), double-entry ledger rows are written in the same transaction, and
`ReconciliationService` can prove the result afterwards. All of that still holds with Redis face down. Failing closed
would convert a cache outage into a **total payments outage** -- we would be the cause of the incident, and the blast
radius would be every customer rather than the handful of abusive ones the limiter exists to slow down.

**What this exposure actually is.** While Redis is down, callers are unthrottled, so the real ceiling becomes what
Postgres and the row locks absorb. Those degrade gracefully under load (transfers queue on the lock; the lock ordering
in both executors keeps them deadlock-free) and they still refuse to overdraw an account. The cost of the outage is
therefore latency and database load, never a wrong balance. That is a trade we are willing to make; the reverse trade
-- refusing legitimate payments to protect a throttle -- is not.

**This is NOT a blanket catch.** Only two conditions fail open, both meaning "Redis did not answer":
`RedisConnectionFailureException` and `QueryTimeoutException` (plus a null/short reply, which means a deferred reply we
cannot interpret). Everything else still propagates and still becomes a 500, on purpose:

- `RedisSystemException` -- this is how a **Lua error inside token_bucket.lua** surfaces. A broken script is our bug,
  and it must not hide behind a warning while the limiter quietly does nothing.
- `ClassCastException` from parsing the reply -- means the script's return contract changed. Same reasoning.

The distinction is the whole point: *unavailable* fails open, *wrong* fails loudly.

**Fail-open only counts if it fails FAST -- and this bit nearly broke the policy.** Lettuce's default command timeout is
**60 seconds**. A Redis that accepts connections but stops answering (failover, packet loss, an overloaded node) would
have hung every transfer for a minute before the limiter gave up and allowed it. Fail-open with a 60s stall is an outage
with extra steps -- the policy would have been correct on paper and useless in production. Fixed by setting
`spring.data.redis.timeout` and `connect-timeout` in `application.yaml`.

**...but not too fast.** The first value tried, 250ms, made it *worse*: the command timeout also bounds Lettuce's
connection handshake, and a cold connection to a perfectly **healthy** local Redis timed out at 250ms. Under fail-open
that failure is invisible -- the limiter simply stops limiting and nobody notices. Settled on **1s**, which sits well
clear of handshake latency while still being 60x better than the default. The general lesson: with a fail-open policy,
an over-tight timeout is not a safety measure, it is a silent shutdown of the protection.

**How we would know.** `RateLimiter` logs at WARN (`"Rate limiter unavailable ... allowing request unthrottled"`) with
the user id, on every fail-open. Alert on the *rate* of that line: a steady stream means the limiter is off entirely.

**Verified by** `RateLimiterTest`: `failsOpenWhenRedisIsUnreachable` (connection refused) and
`failsOpenFastWhenRedisAcceptsButNeverAnswers` (a socket that accepts and never replies -- asserts both `allowed` and
that it returns in well under the old 60s).

**Known gaps, deliberately left:**

- **No metric.** The WARN log is the only signal, and logs are a weak thing to page on. A counter
  (`ratelimit.failopen`) would be better, but `pom.xml` has neither actuator nor micrometer, so that is a real
  dependency decision rather than a one-liner.
- **No circuit breaker.** While Redis hangs, every request pays the 1s timeout; a breaker would skip the call after N
  consecutive failures and cut that to zero. Worth adding if a Redis outage ever actually happens in anger.

**Revisit when:** the limiter stops being purely about capacity. If it ever enforces something that protects *money*
rather than *servers* -- withdrawal velocity limits, fraud throttles, anything where letting the request through is the
expensive outcome -- then that check must fail **closed**, and it should be a separate component so this policy does not
silently apply to it.
