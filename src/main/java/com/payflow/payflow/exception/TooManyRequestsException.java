package com.payflow.payflow.exception;

/**
 * Thrown when a caller has spent their rate limit bucket.
 *
 * Carries the Retry-After value so the handler can set the header without knowing
 * anything about buckets or refill rates -- the limiter computed it, the handler just
 * writes it down.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Too many requests. Retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
