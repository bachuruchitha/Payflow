package com.payflow.payflow.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

@Configuration
public class RateLimiterConfig {

    /**
     * The token bucket script, read from the classpath once at startup.
     *
     * DefaultRedisScript also computes the SHA1 once and lazily caches it, so requests
     * go out as EVALSHA (just the digest) rather than shipping the whole script body
     * every time. Spring falls back to EVAL automatically if the server reports NOSCRIPT
     * -- which is what happens after a Redis restart or a SCRIPT FLUSH.
     */
    @Bean
    public RedisScript<List<Object>> tokenBucketScript() {
        DefaultRedisScript<List<Object>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));

        // The script returns { allowed, tokens } -- a heterogeneous list (Long, String).
        // Class literals can't carry type arguments, so the parameterised type has to be
        // reached through a raw cast. The unchecked-ness stops here rather than leaking
        // out as a raw RedisScript<List> that every caller has to cast element by element.
        @SuppressWarnings("unchecked")
        Class<List<Object>> resultType = (Class<List<Object>>) (Class<?>) List.class;
        script.setResultType(resultType);

        return script;
    }

    /**
     * Injected wherever "now" is needed, so tests can substitute a fixed or offset clock
     * and drive the bucket's refill deterministically instead of sleeping.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
