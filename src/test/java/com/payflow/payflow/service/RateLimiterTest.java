package com.payflow.payflow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RateLimiterTest {

    private static final int    CAPACITY          = 10;
    private static final double REFILL_PER_SECOND = 0.16666666667;   // 10 per minute

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    RedisScript<List<Object>> tokenBucketScript;

    @Autowired
    StringRedisTemplate redisTemplate;

    String userId;

    @BeforeEach
    void freshBucket() {
        userId = UUID.randomUUID().toString();
        redisTemplate.delete("rate_limit:transfer:" + userId);
    }

    /** A clock the test moves by hand, so refill is exercised without sleeping. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-19T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private RateLimiter limiterWith(Clock clock) {
        return new RateLimiter(redisTemplate, tokenBucketScript, clock, CAPACITY, REFILL_PER_SECOND);
    }

    @Test
    void spendsTheWholeBucketThenRejects() {
        // A fixed clock means zero refill during the test: each call costs exactly one
        // token, with no wall-clock drift to tolerate.
        RateLimiter limiter = limiterWith(new MutableClock());

        for (int i = CAPACITY - 1; i >= 0; i--) {
            RateLimitResult result = limiter.check(userId);
            assertThat(result.allowed()).isTrue();
            assertThat(result.tokensLeft()).isCloseTo(i, org.assertj.core.data.Offset.offset(1e-9));
        }

        RateLimitResult rejected = limiter.check(userId);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.tokensLeft()).isZero();
    }

    @Test
    void refillsOverTimeAndLetsTheCallerBackIn() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = limiterWith(clock);

        for (int i = 0; i < CAPACITY; i++) {
            limiter.check(userId);
        }
        assertThat(limiter.check(userId).allowed()).isFalse();

        // One token takes 1 / 0.1667 = 6s to accrue. At 5s there is still not a whole one.
        clock.advance(Duration.ofSeconds(5));
        assertThat(limiter.check(userId).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.check(userId).allowed()).isTrue();
    }

    @Test
    void retryAfterIsWholeSecondsUntilTheNextToken() {
        RateLimiter limiter = limiterWith(new MutableClock());

        // Empty bucket: a full token away, 1 / 0.1667 = 6s.
        assertThat(limiter.retryAfterSeconds(new RateLimitResult(false, 0.0))).isEqualTo(6L);

        // Partly refilled: 0.5 tokens needed, 3s.
        assertThat(limiter.retryAfterSeconds(new RateLimitResult(false, 0.5))).isEqualTo(3L);

        // Never 0 -- that would invite an immediate retry into another rejection.
        assertThat(limiter.retryAfterSeconds(new RateLimitResult(false, 0.999999))).isEqualTo(1L);
        assertThat(limiter.retryAfterSeconds(new RateLimitResult(false, 1.0))).isEqualTo(1L);
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        // Port 6390 has nothing listening: the template throws
        // RedisConnectionFailureException, which is the one condition we allow through.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory deadFactory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6390), clientConfig);
        deadFactory.afterPropertiesSet();

        try {
            RateLimiter limiter = new RateLimiter(new StringRedisTemplate(deadFactory),
                    tokenBucketScript, new MutableClock(), CAPACITY, REFILL_PER_SECOND);

            RateLimitResult result = limiter.check(userId);

            // Availability of payments does not depend on the limiter being up.
            assertThat(result.allowed()).isTrue();
        } finally {
            deadFactory.destroy();
        }
    }

    @Test
    void failsOpenFastWhenRedisAcceptsButNeverAnswers() throws Exception {
        // The nastier outage, and the one a connection-refused test cannot catch: the
        // socket connects fine and then nothing comes back -- a failover, packet loss, an
        // overloaded node. Lettuce's DEFAULT command timeout is 60s, so without an explicit
        // timeout every transfer would block for a minute before the limiter gave up.
        // Fail-open that takes 60s is not fail-open; it is an outage with extra steps.
        try (ServerSocket blackHole = new ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try {
                    // Accept and hold: never write a byte back.
                    Socket held = blackHole.accept();
                    Thread.sleep(30_000);
                    held.close();
                } catch (Exception ignored) {
                    // Socket closed by the test finishing -- expected.
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofSeconds(1))   // matches application.yaml
                    .shutdownTimeout(Duration.ZERO)
                    .build();
            LettuceConnectionFactory hungFactory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("localhost", blackHole.getLocalPort()), clientConfig);
            hungFactory.afterPropertiesSet();

            try {
                RateLimiter limiter = new RateLimiter(new StringRedisTemplate(hungFactory),
                        tokenBucketScript, new MutableClock(), CAPACITY, REFILL_PER_SECOND);

                long startedAt = System.nanoTime();
                RateLimitResult result = limiter.check(userId);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

                assertThat(result.allowed()).isTrue();
                // Generous ceiling: the point is "about a timeout", not "about a minute".
                assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            } finally {
                hungFactory.destroy();
            }
        }
    }

    @Test
    void bucketsAreIsolatedPerUser() {
        RateLimiter limiter = limiterWith(new MutableClock());

        for (int i = 0; i < CAPACITY; i++) {
            limiter.check(userId);
        }
        assertThat(limiter.check(userId).allowed()).isFalse();

        // A different user must be unaffected by the first user's spending.
        String otherUser = UUID.randomUUID().toString();
        redisTemplate.delete("rate_limit:transfer:" + otherUser);
        assertThat(limiter.check(otherUser).allowed()).isTrue();
    }

    @Test
    void concurrentCallsCannotOverspendTheBucket() throws InterruptedException {
        // The point of running the read-refill-decide-write in Lua: 50 threads racing on
        // one bucket must still only get CAPACITY allows between them.
        int threads = 50;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (rateLimiter.check(userId).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(CAPACITY);
    }
}
