# 11 — Balance Cache (Redis)

**Files:** `service/BalanceCache.java`, `service/WalletService.java` (`getDerivedBalance`), eviction calls in `WalletService.topUp`,
`TransferExecutor`, `OptimisticTransferExecutor`. Test: `TopUpBalanceConsistencyTest`.

## 11.1 What is cached

The **ledger-derived balance** (chapter 06). Summing a wallet's ledger entries gets slower as history grows, so the result is cached:

| Property | Value |
|---|---|
| Key | `wallet:balance:<walletId>` |
| Value | `BigDecimal.toPlainString()`, e.g. `"400.0000"` |
| TTL | 5 seconds |
| Pattern | **Cache-aside** (read-through): on a miss, compute from the DB and `put` |
| Invalidation | **Evict after commit** on every write that changes the balance |

```java
public BigDecimal getDerivedBalance(UUID walletId) {
    BigDecimal cached = balanceCache.get(walletId);
    if (cached != null) return cached;
    BigDecimal amount = ledgerEntryRepository.computeBalanceFromLedger(walletId);
    balanceCache.put(walletId, amount);
    return amount;
}
```

> **Important:** no endpoint uses `getDerivedBalance`. `GET /api/wallets/me` serves the stored column. The cache exists and is
> tested, but it's display-only by design and **can never influence a money decision** (`DECISIONS.md`).

## 11.2 `BalanceCache`: one owner for the rules

`BalanceCache` is the **single owner** of the key format, the TTL and, most importantly, **when entries are dropped**. Keeping the rule
in one class means a new write path can't get it subtly wrong.

### The rule: evict only AFTER commit

```java
public void evictAfterCommit(UUID... walletIds) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        redisTemplate.delete(keys);           // no transaction: the write is already visible, evict now
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { redisTemplate.delete(keys); }
    });
}
```

Why not evict immediately inside the transaction?

**Evict before commit → stale cache for the full TTL:**
```
T (transfer)                        Reader
evict wallet:balance:A
                                    cache miss → SUM ledger → sees OLD balance (T not committed)
                                    put OLD balance (TTL 5 s)
commit
                                    → cache serves the OLD balance for up to 5 s
```

**Evict, then roll back → cache repopulated from a state that never existed.** This matters most in the optimistic executor, where an
attempt can fail its version check **at commit**.

`afterCommit` callbacks run only if the commit **succeeds**. On rollback they never run, and the cache is left alone, which is
correct because nothing changed.

## 11.3 The remaining (accepted) race

Even with evict-after-commit, cache-aside has a classic narrow race:

```
Reader: miss → computes SUM (sees pre-commit value) ……… put(old value)
Writer:                         commit → afterCommit: evict
```

If the reader's `put` lands **after** the writer's evict, the stale value survives until the 5 s TTL expires. `DECISIONS.md`
accepts this **only because the value is display-only**, and states: *if the wallet endpoint is ever switched to serve it, that race has
to be closed first* (for example with versioned cache values, or a short delete-after-delay, or by caching the stored column instead).

## 11.4 Redis failure behaviour

Unlike the rate limiter, `BalanceCache` has **no fail-open handling**:
- `get`/`put` failures propagate from `getDerivedBalance` (no endpoint calls it, so no user impact today).
- An `afterCommit` eviction failure happens **after** the DB commit. The transfer is already durable, but the exception propagates from
  the commit phase, so the client could see a 500 for a transfer that succeeded (it can recover by retrying with the same idempotency key).
  Worth wrapping in try/catch + WARN if the cache is ever used for real (chapter 17).

## 11.5 Test: `TopUpBalanceConsistencyTest`

1. Warm the cache (`getDerivedBalance`) and assert the key exists.
2. Top up 1000.
3. Assert the key is **gone** (evicted after commit).
4. Assert stored balance == derived balance == 1000, and reconciliation reports no mismatch.
5. A second test does 5 top-ups of 13.37 and checks 66.85 everywhere.
