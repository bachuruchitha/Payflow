package com.payflow.payflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Per-user token bucket, evaluated inside Redis by scripts/token_bucket.lua.
 *
 * The read-refill-decide-write sequence has to be atomic: two concurrent requests from
 * the same user that both read "1 token left" would both spend it. Running it as a Lua
 * script keeps it atomic without a round trip per step, because Redis runs the whole
 * script as one unit -- nothing else touches the bucket in between.
 */
@Service
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "rate_limit:transfer:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Object>> tokenBucketScript;
    private final Clock clock;
    private final int capacity;
    private final double refillPerSecond;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       RedisScript<List<Object>> tokenBucketScript,
                       Clock clock,
                       @Value("${payflow.ratelimit.capacity}") int capacity,
                       @Value("${payflow.ratelimit.refill-per-second}") double refillPerSecond) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /**
     * Spends one token for this user, if there is one.
     *
     * Not idempotent: every call that returns allowed=true has consumed a token, so call
     * it once per request and pass the result along. Calling it twice for one request
     * charges the user twice.
     *
     * FAIL-OPEN: if Redis is unreachable or too slow to answer, this allows the request
     * and logs a warning. Rate limiting is a protective measure, not part of the payment
     * invariant -- a Redis outage must not stop customers moving their own money. The
     * exposure this accepts is bounded and deliberate: while Redis is down, callers are
     * unthrottled, so the abuse ceiling becomes whatever Postgres and the row locks in
     * TransferExecutor can absorb. Those still enforce correctness (no overdraft, no lost
     * update); only the throttle is lost, never the ledger's integrity.
     *
     * Note the asymmetry with the fail-closed choice a fraud check would deserve: this is
     * safe to fail open precisely because the limiter protects capacity, not correctness.
     */
    public RateLimitResult check(String userId) {
        // KEYS[1] -- one bucket per user. The key is built here and nowhere else, so the
        // script can never be handed a key in a different shape.
        List<String> keys = List.of(KEY_PREFIX + userId);

        // ARGV -- StringRedisTemplate speaks strings, and so does Redis: the script
        // tonumber()s these back on the other side.
        //
        // The clock is injected rather than read via Instant.now() so tests can advance
        // time instead of sleeping. It must stay a wall-clock source shared by every
        // instance: the script is a pure function of the timestamps we hand it (see the
        // comment at the top of token_bucket.lua), so a per-instance or monotonic clock
        // would make instances disagree about the refill.
        String capacityArg = String.valueOf(capacity);
        String refillArg   = String.valueOf(refillPerSecond);
        String nowArg      = String.valueOf(clock.millis());

        List<Object> result;
        try {
            // One round trip: EVALSHA, or EVAL the first time / after a Redis restart.
            result = redisTemplate.execute(tokenBucketScript, keys, capacityArg, refillArg, nowArg);
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            // Deliberately narrow. These two mean "Redis did not answer": connection
            // refused, pool exhausted, node failing over, command timed out.
            //
            // Everything else still propagates on purpose, because it would mean the call
            // itself is wrong rather than unavailable: a RedisSystemException carries a
            // Lua error from the script, and a ClassCastException below means the reply
            // no longer matches the contract. Failing those open would hide a broken
            // limiter behind a warning nobody reads, and we would find out from a bill.
            return failOpen(userId, e);
        }

        if (result == null || result.size() < 2) {
            // A null reply is not the outage path (that throws, above) -- it is what a
            // deferred reply looks like, i.e. the script was queued inside a pipeline or
            // MULTI. Either way we cannot tell whether a token was spent, so the same
            // fail-open policy applies: unknown state must not block a payment.
            return failOpen(userId, null);
        }

        // allowed comes back as a Long (Redis integer reply); tokens as a String, because
        // the script tostring()s it to keep the fractional part -- Redis truncates Lua
        // numbers to integers on the way out.
        Long allowedRaw  = (Long) result.get(0);
        String tokensRaw = (String) result.get(1);

        boolean allowed   = allowedRaw == 1L;
        double tokensLeft = Double.parseDouble(tokensRaw);

        return new RateLimitResult(allowed, tokensLeft);
    }

    /**
     * Whole seconds the caller should wait before retrying: how long until the bucket
     * holds one full token again.
     *
     * Floored at 1. A rejected caller told "Retry-After: 0" would retry immediately and
     * be rejected again, which is worse than useless -- it turns our own 429 into a
     * retry storm.
     */
    public long retryAfterSeconds(RateLimitResult result) {
        double tokensNeeded = 1.0 - result.tokensLeft();
        long seconds = (long) Math.ceil(tokensNeeded / refillPerSecond);
        return Math.max(1L, seconds);
    }

    private RateLimitResult failOpen(String userId, Exception cause) {
        // WARN, not ERROR: the request is being served correctly, but unthrottled. This is
        // the line to alert on -- a steady stream of it means the limiter is off entirely.
        if (cause == null) {
            log.warn("Rate limiter unavailable (no usable reply from Redis) for user {}; allowing request unthrottled", userId);
        } else {
            log.warn("Rate limiter unavailable for user {}; allowing request unthrottled", userId, cause);
        }
        // No token was spent, so as far as the bucket is concerned nothing was consumed.
        // Reporting capacity keeps that honest; it is never used to build a Retry-After,
        // because this path always reports allowed=true.
        return new RateLimitResult(true, capacity);
    }
}
