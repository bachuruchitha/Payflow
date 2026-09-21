package com.payflow.payflow.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The single owner of the cached derived balances in Redis: the key format, the TTL,
 * and -- the part that actually matters for money safety -- WHEN an entry is dropped.
 *
 * Every write path that changes a wallet's balance has to evict that wallet's cached
 * value, and has to do it only AFTER the database transaction commits. Evicting before
 * commit re-opens the window it was meant to close: a reader that misses the cache in
 * that window recomputes from the ledger, sees the pre-transfer balance (our entries
 * aren't committed yet), and caches that stale number for the full TTL. Rolling back
 * after an early evict is just as wrong -- the cache would be repopulated from a state
 * the wallet never left.
 *
 * Keeping the rule in one place means a new write path can't quietly get it wrong.
 */
@Component
public class BalanceCache {

    private static final String KEY_PREFIX = "wallet:balance:";

    private static final Duration TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;

    public BalanceCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Cached balance for this wallet, or null on a miss. */
    public BigDecimal get(UUID walletId) {
        String value = redisTemplate.opsForValue().get(key(walletId));
        return value == null ? null : new BigDecimal(value);
    }

    public void put(UUID walletId, BigDecimal balance) {
        redisTemplate.opsForValue().set(key(walletId), balance.toPlainString(), TTL);
    }

    /**
     * Drop the cached balances for these wallets once the current transaction commits.
     * If the transaction rolls back the callback never runs, so the cache is left alone
     * -- which is correct, because nothing changed.
     */
    public void evictAfterCommit(UUID... walletIds) {
        List<String> keys = Arrays.stream(walletIds).map(BalanceCache::key).toList();

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for (a caller outside @Transactional): the write is
            // already visible, so evicting now is both safe and necessary.
            redisTemplate.delete(keys);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisTemplate.delete(keys);
                    }
                }
        );
    }

    private static String key(UUID walletId) {
        return KEY_PREFIX + walletId;
    }
}
