package com.payflow.payflow.service;

/**
 * Outcome of one rate limit check: whether to let the request through, and how much of
 * the caller's bucket is left.
 *
 * tokensLeft is fractional on purpose -- the bucket refills continuously, so "0.4 tokens"
 * is a real state and means the next token is 0.6 refill-intervals away. Step 4 needs
 * that to compute Retry-After.
 */
public record RateLimitResult(boolean allowed, double tokensLeft) { }
