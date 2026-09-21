# 09 — Idempotency

**Files:** `controller/TransferController.java` (header), `service/TransferService.java` (`withIdempotency`),
`entity/Transaction.java` (`idempotencyKey`), `repository/TransactionRepository.java` (`findByIdempotencyKey`),
migration `V6__add_idempotency_key_to_transactions.sql`

## 9.1 The problem

A client sends "transfer 100", and then:
- the network drops the **response** (the transfer *did* happen), or
- the user double-clicks "Pay", or
- a mobile app automatically retries after a timeout.

Without protection, each retry is a **new** transfer and the user pays twice. The client can't tell "it failed" apart from "it
worked but I didn't hear back".

**Idempotent** means doing the operation N times has the same effect as doing it once.

## 9.2 The contract

The client generates a **unique key per logical payment** (typically a UUID) and sends it as a header:

```http
Idempotency-Key: 6f1c9a3e-4b0e-4a57-9e0b-2d0f2a1c7b11
```

- First request with the key: the transfer executes and the key is stored on the `transactions` row.
- Any later request with the **same key**: nothing executes; the server returns the **original** transaction's
  `{transactionId, status, amount}` with `200`.

This is the same pattern Stripe, Adyen and PayPal use.

## 9.3 Implementation: two layers

### Layer 1: fast path lookup (application)

```java
Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
if (existing.isPresent()) return toResponse(existing.get());
```

This handles the common case: a retry arriving *after* the first request committed.

### Layer 2: the real guarantee (database)

```sql
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key);
```

Two identical requests arriving **at the same moment** can both pass layer 1 (neither sees a committed row yet). Both try to
INSERT; the UNIQUE index lets exactly one commit. The loser gets a constraint violation at flush/commit, which Spring translates
to `DataIntegrityViolationException`:

```java
} catch (DataIntegrityViolationException e) {
    return toResponse(transactionRepository.findByIdempotencyKey(idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but no committed transaction found: " + key)));
}
```

The loser re-reads the winner's committed row and **replays** it. The client never sees an error.

**Principle:** a Java check alone is a race. The database constraint is the one thing that can't be bypassed by timing.

### Where idempotency sits relative to retries

`withIdempotency` wraps the **whole** retry loop (`TransferService.transferOptimistic` → `OptimisticTransferService`). The
retry loop catches only `ObjectOptimisticLockingFailureException`, so a `DataIntegrityViolationException` passes straight through it
to `withIdempotency`, which replays the winner. The duplicate-key case is handled **once, outside**, and never retried.

## 9.4 The migration: adding a NOT NULL UNIQUE column safely (V6)

```sql
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);             -- 1. nullable, so existing rows are OK
UPDATE transactions SET idempotency_key = id::text WHERE idempotency_key IS NULL; -- 2. backfill unique values
ALTER TABLE transactions ALTER COLUMN idempotency_key SET NOT NULL;           -- 3. now enforce NOT NULL
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key); -- 4. enforce uniqueness
```

Adding a `NOT NULL` column directly would fail on a table that already has rows. This add → backfill → constrain sequence is the
standard zero-downtime pattern.

## 9.5 Scenarios

| Scenario | What happens | Response |
|---|---|---|
| New key | Executes | 200, new transaction |
| Same key, after the first committed | Layer 1 finds it | 200, original transaction (no money moves) |
| Same key, two requests at the same instant | One commits, the other hits UNIQUE → replay | Both 200 with the **same** transactionId |
| First attempt failed (e.g. insufficient balance) | The failed attempt rolled back, so the key was **never stored** | A retry with the same key executes again (correct: nothing happened the first time) |
| First attempt got `409 TRANSFER_CONFLICT` | Rolled back, key not stored | Safe to retry with the same key |

## 9.6 Known gaps (verified in the code; fixes in chapter 17)

1. **Keys are global, not per user.** The UNIQUE constraint and the lookup ignore who sent the request. If user B happens to send
   a key user A already used, B gets **200 COMPLETED with A's transaction id and amount**. B's money never moves, B believes it
   did, and A's data leaks to B. Fix: `UNIQUE (from_wallet_id, idempotency_key)` and a lookup scoped to the sender.
2. **The request body isn't compared.** Reusing a key with a *different* amount or receiver silently returns the old result.
   Stripe returns an error in that case. Fix: store a hash of the request and return `422` on mismatch.
3. **Concurrent duplicate + low balance.** Two identical requests race: A commits and spends the balance. B then reads the
   *new* balance and throws `InsufficientBalanceException` **before** reaching the INSERT that would have triggered the replay. The
   client (which retried because it never saw A's answer) is told the payment failed, although it succeeded. Fix: re-check the key
   **after** acquiring the wallet lock (pessimistic) or before the balance check in each attempt (optimistic).
4. **Any other integrity violation is misread as a duplicate.** If the INSERT fails for another reason (for example an
   amount that rounds to `0.0000` and breaks `CHECK (amount > 0)`), the catch looks up the key, finds nothing, and throws
   `IllegalStateException` → **500**.
5. **Idempotent replays still spend a rate-limit token.** The rate check runs before the idempotency lookup, so an honest client
   retrying with the same key is throttled like a new request.
6. **Missing header → 500** instead of 400 (`MissingRequestHeaderException` falls through to the catch-all handler).
7. **Top-up is not idempotent**: it has no key at all.
