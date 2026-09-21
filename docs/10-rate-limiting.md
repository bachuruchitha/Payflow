# 10 — Rate Limiting (Redis + Lua Token Bucket)

**Files:** `service/RateLimiter.java`, `service/RateLimitResult.java`, `configuration/RateLimiterConfig.java`,
`src/main/resources/scripts/token_bucket.lua`, `exception/TooManyRequestsException.java`, `GlobalExceptionHandler` (429 handler),
`application.yaml` (`payflow.ratelimit.*`, `spring.data.redis.timeout`). Tests: `RateLimiterTest`.
Decisions: `DECISIONS.md` Day 21 (three entries).

## 10.1 Why rate limit

To stop one client (a buggy retry loop, a script, an attacker) from flooding the transfer endpoint and taking database
connections and row locks away from everyone else. **It protects capacity, not correctness**: the money invariants hold with or
without it. That distinction drives the fail-open decision below.

Scope: **only `POST /api/transfers`**, one bucket **per authenticated user**.

## 10.2 The token bucket algorithm

Picture a bucket that holds up to **capacity** tokens and refills continuously at **refill-per-second** tokens per second.

- Each request takes one token. If there is ≥ 1 token → allowed. Otherwise → rejected.
- A full bucket allows a **burst** of `capacity` requests. After that, the **sustained rate** is the refill rate.

PayFlow's settings (`application.yaml`):

| Setting | Value | Meaning |
|---|---|---|
| `capacity` | 10 | Up to 10 transfers back-to-back |
| `refill-per-second` | 0.16666666667 | 1 token every 6 s = 10 per minute sustained |

**Lazy refill:** nothing ticks in the background. On each request the script computes
`tokens = min(capacity, tokens + elapsedSeconds × refillPerSecond)`. Idle buckets cost nothing.

Compared with alternatives:
- *Fixed window* ("10 per calendar minute") allows 20 requests in 2 seconds across a window boundary.
- *Sliding log* is exact but stores every timestamp.
- The token bucket is smooth, O(1) in memory (two numbers per user), and allows controlled bursts.

## 10.3 Why a Lua script in Redis

The steps are read → refill → decide → write. Done as separate Redis commands from Java, two concurrent requests from the same
user could both read "1 token left" and both spend it (the same TOCTOU race as chapter 08, one level up). **Redis runs a Lua
script atomically**: no other command touches the bucket until the script finishes. The whole check is atomic and takes one
network round trip.

### `token_bucket.lua` walkthrough

```lua
-- KEYS[1] = rate_limit:transfer:<userId>   ARGV = capacity, refillPerSecond, nowMs
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')     -- 1. read state (a hash with 2 fields)
if tokens == nil or ts == nil then tokens = capacity; ts = now end  -- 2. first request: FULL bucket
local elapsedSeconds = (now - ts) / 1000
if elapsedSeconds < 0 then elapsedSeconds = 0 end                -- 3. clock went backwards? never refill negatively
tokens = math.min(capacity, tokens + elapsedSeconds * refillPerSecond)
if tokens >= 1 then tokens = tokens - 1; allowed = 1 end         -- 4. spend one
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)         -- 5. persist; ts moves even on reject
redis.call('PEXPIRE', KEYS[1], ceil(capacity / refill * 1000) + 1000)  -- self-cleaning TTL
return { allowed, tostring(tokens) }                              -- 6. tokens as STRING
```

Design details, each deliberate:

| Detail | Why |
|---|---|
| `now` is passed in, not read with `redis.call('TIME')` | Keeps the script **deterministic** (a pure function of its inputs), which is safe to replicate. Trade-off: every app instance must share the same wall clock. The Java side injects a `Clock` bean, which tests can replace |
| Negative elapsed time clamped to 0 | An NTP correction or a skewed host could otherwise *drain* the bucket |
| `ts` updated even on a rejected request | The refill was already added to `tokens`. Leaving `ts` behind would credit the same elapsed time twice |
| TTL = time to refill from empty + 1 s | Idle keys disappear. Expiring any sooner would hand back a full bucket early and forgive tokens already spent |
| `tostring(tokens)` | Redis converts Lua numbers to **integers** on the way out, which would lose the fraction (`0.4` → `0`). The fraction is needed for `Retry-After` |

### Loading the script: `RateLimiterConfig`

- `DefaultRedisScript` reads `scripts/token_bucket.lua` once and caches its SHA1. Calls go out as **`EVALSHA <sha>`** (small),
  and Spring falls back to `EVAL <full script>` automatically on `NOSCRIPT` (after a Redis restart or `SCRIPT FLUSH`).
- The result type is `List<Object>`, a heterogeneous `[Long, String]`. The unchecked generic cast is confined to this one bean.
- A `Clock` bean (`Clock.systemUTC()`) is defined here so tests can inject a controllable clock.

## 10.4 `RateLimiter.check(userId)`

```java
keys = List.of("rate_limit:transfer:" + userId);                 // key built in ONE place
result = redisTemplate.execute(tokenBucketScript, keys, capacity, refill, clock.millis());   // all args as strings
allowed    = (Long) result.get(0) == 1L;
tokensLeft = Double.parseDouble((String) result.get(1));
return new RateLimitResult(allowed, tokensLeft);
```

- **`StringRedisTemplate`, not `RedisTemplate`** (`DECISIONS.md`): Redis passes script arguments as text anyway, and a
  JDK-serialising template would mangle keys into binary, so `redis-cli` couldn't find `rate_limit:transfer:<id>`.
- **Returns a record, not a boolean** (`DECISIONS.md`): the 429 needs `Retry-After`, which needs the fractional `tokensLeft`.
  The record also keeps the raw-`List` casting in one place.
- **Not idempotent**: each call spends a token, so the controller calls it exactly once per request.

## 10.5 `Retry-After`

```java
public long retryAfterSeconds(RateLimitResult r) {
    double tokensNeeded = 1.0 - r.tokensLeft();
    return Math.max(1L, (long) Math.ceil(tokensNeeded / refillPerSecond));
}
```

- Empty bucket (0.0 left) → 1 / 0.1667 = **6 s**. At 0.5 left → **3 s**.
- **Floored at 1 s**: a `Retry-After: 0` would invite an immediate retry, and that retry would be rejected too, which starts a retry storm.

The controller throws `TooManyRequestsException(seconds)`. The handler returns:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 6

{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Retry after 6s","timestamp":"…"}
```

## 10.6 Fail-open policy: what happens when Redis is down

**Decision:** if Redis can't answer, **allow the request** and log a WARN.

**Why:** the limiter protects capacity, not money. Overdraft is prevented by the row lock / version check, the ledger by the same
transaction, and reconciliation proves it afterwards. None of those depend on Redis. Failing *closed* would turn a cache outage into a
**total payments outage**, hurting every customer to slow down a few abusive ones.

**Narrow on purpose:** only "Redis didn't answer" fails open:

| Condition | Behaviour | Why |
|---|---|---|
| `RedisConnectionFailureException` (refused, pool exhausted, failover) | **Allow** + WARN | Unavailable |
| `QueryTimeoutException` (command timed out) | **Allow** + WARN | Unavailable |
| `null` or short reply (pipelined/MULTI context) | **Allow** + WARN | State unknown |
| `RedisSystemException` (a **Lua error**) | **500** | The script is broken. That's our bug and must be loud |
| `ClassCastException` parsing the reply | **500** | The script's return contract changed |

*Unavailable* fails open; *wrong* fails loudly.

**Fail-open only counts if it fails fast.** Lettuce's default command timeout is 60 s, so a Redis that accepts connections but
never replies would stall every transfer for a minute. Hence `spring.data.redis.timeout: 1s`. (250 ms was tried and broke healthy cold
connections; under fail-open that failure was **invisible**, because the limiter just stopped limiting.)

Monitoring: alert on the **rate** of the log line `Rate limiter unavailable … allowing request unthrottled`.

Known gaps (documented in `DECISIONS.md`): no metric counter (no actuator/micrometer yet), and no circuit breaker (every request pays
the 1 s timeout while Redis hangs).

**When this policy must change:** if a future limiter protects *money* (withdrawal velocity limits, fraud throttles), it must fail
**closed** and be a separate component.

## 10.7 Tests (`RateLimiterTest`, needs local Redis)

| Test | Proves |
|---|---|
| `spendsTheWholeBucketThenRejects` | 10 allowed with `tokensLeft` 9…0, then rejected (fixed clock, so no refill) |
| `refillsOverTimeAndLetsTheCallerBackIn` | After draining: at +5 s still rejected, at +6 s allowed (a `MutableClock` moves time instead of sleeping) |
| `retryAfterIsWholeSecondsUntilTheNextToken` | 6 s, 3 s, and the floor of 1 |
| `failsOpenWhenRedisIsUnreachable` | Nothing listening on port 6390 → allowed |
| `failsOpenFastWhenRedisAcceptsButNeverAnswers` | A "black hole" socket that accepts and never replies → allowed in < 5 s (not 60) |
| `bucketsAreIsolatedPerUser` | One user's empty bucket doesn't affect another |
| `concurrentCallsCannotOverspendTheBucket` | 50 threads on one bucket → exactly 10 allowed (proves atomicity) |
