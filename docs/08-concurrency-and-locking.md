# 08 — Concurrency: Pessimistic vs Optimistic Locking

**Files:** `service/TransferExecutor.java` (pessimistic), `service/OptimisticTransferExecutor.java` +
`service/OptimisticTransferService.java` (optimistic), `repository/WalletRepository.java`, `entity/Wallet.java` (`@Version`).
Tests: `ConcurrentTransferTest`, `LockingStrategyComparisonTest`. Docs: root `README.md`, `DECISIONS.md` (Day 19).

## 8.1 The problem: lost updates / TOCTOU

Without any locking, 50 simultaneous transfers of 100 from a wallet holding 1000 would do this:

```
T1: SELECT balance → 1000     T2: SELECT balance → 1000    … T50: SELECT balance → 1000
T1: 1000 ≥ 100 ✓              T2: 1000 ≥ 100 ✓             … T50 ✓
T1: UPDATE balance = 900      T2: UPDATE balance = 900     … T50: UPDATE balance = 900
```

All 50 succeed and 5000 is sent from 1000. Worse, the stored balance ends at 900 while the ledger has 50 debits. This is a
**time-of-check to time-of-use (TOCTOU)** race: the balance *checked* is no longer the balance at the moment it is *used*.
PostgreSQL's default isolation level (READ COMMITTED) does **not** prevent it.

PayFlow has two interchangeable fixes.

## 8.2 Strategy 1: Pessimistic locking (`SELECT … FOR UPDATE`)

"Assume conflict; lock before reading."

```java
// WalletRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.id = :id")
Optional<Wallet> findByIdForUpdate(UUID id);     // → SELECT … FROM wallets WHERE id=? FOR UPDATE
```

In `TransferExecutor`:

```java
UUID senderWalletId = walletRepository.findWalletIdByUserId(senderId)...;   // id only, no entity loaded
Wallet first  = walletRepository.findByIdForUpdate(firstId)...;            // lock the lower id
Wallet second = walletRepository.findByIdForUpdate(secondId)...;           // then the higher id
BigDecimal currentBalance = senderWallet.getBalance();                      // read ONLY after the lock is held
```

- `FOR UPDATE` makes any other transaction that tries to lock the same row **wait** until this one commits or rolls back.
- The next transfer to get the lock reads the **true, post-previous-transfer** balance.
- Throwing `InsufficientBalanceException` rolls back and **releases the lock immediately**.

**Why `findWalletIdByUserId` returns just a `UUID`:** if the code first loaded the `Wallet` entity with a plain SELECT, Hibernate would
cache that unlocked (possibly stale) copy in the persistence context. The scalar projection makes the **locked fetch the first
time the row is read**, so there's no stale copy around.

Result: with 50 threads, exactly 10 succeed and 40 get `InsufficientBalance`. Deterministic, every time.

## 8.3 Strategy 2: Optimistic locking (`@Version` + retry)

"Assume no conflict; detect it at commit."

```java
// Wallet
@Version
@Column(name = "version", nullable = false)
private Integer version;
```

The executor reads the wallets with a plain `findById` (no lock). At commit, Hibernate writes:

```sql
UPDATE wallets SET balance = ?, version = 8 WHERE id = ? AND version = 7
```

If another transaction committed in between, `version` is no longer 7, **0 rows** match, Hibernate throws, the whole
attempt rolls back, and Spring surfaces `ObjectOptimisticLockingFailureException`. `OptimisticTransferService` then retries in a
**fresh transaction** (up to 3 attempts, jittered backoff), and gives up with `409 TRANSFER_CONFLICT`.

The stale balance read in the executor is harmless for *overdraft* purposes: any committed change to the wallet bumps the version,
so a decision based on a stale value can never commit.

> Both strategies coexist safely: `@Version` is on the entity, so the pessimistic executor also bumps the version on every write.
> A pessimistic transfer therefore correctly invalidates any concurrent optimistic attempt, or top-up, on the same wallet.

## 8.4 Deadlock prevention: global lock ordering

**Deadlock:** T1 (A→B) locks A and waits for B, while T2 (B→A) locks B and waits for A. Neither can proceed. PostgreSQL detects
the cycle and aborts one of them with an error.

**Fix used by both executors:** always touch the two wallets in **ascending wallet-id order**, whatever the transfer's direction:

```java
UUID firstId  = senderWalletId.compareTo(receiverWalletId) < 0 ? senderWalletId : receiverWalletId;
UUID secondId = senderWalletId.compareTo(receiverWalletId) < 0 ? receiverWalletId : senderWalletId;
```

- **Pessimistic:** the `FOR UPDATE` locks are taken in that order.
- **Optimistic:** the reads take no locks, but the `UPDATE`s at commit *do* lock rows until commit, and Hibernate issues them
  **in the order the entities were loaded**. Loading in id order makes the UPDATEs follow the same global order.

Why this works: a deadlock needs a **cycle** of waits. If every transaction takes locks in one global order, each one only ever
waits for a lock that comes *later* in that order than any it already holds, so a cycle can't form.

(`UUID.compareTo` compares the two 64-bit halves as signed longs. That isn't the same as PostgreSQL's UUID ordering, but it doesn't
need to be. It only has to be the same **total order everywhere in the app**.)

## 8.5 The measured comparison

From root `README.md` (Day 19). 50 threads each send 100 from one wallet that funds exactly 10, Hikari pool of 10 connections,
10 rounds per strategy:

| | Pessimistic | Optimistic (3 attempts) |
|---|---|---|
| Transfers completed | 10 every round | 10 every round |
| "Insufficient balance" | 40 | 27–31 |
| Gave up (`409 TRANSFER_CONFLICT`) | 0 | 9–13 |
| Retries | 0 | exactly 90 every round (10 wins × 9 losers) |
| Time for all 50 requests, median (range) | 1.9 s (1.4–3.1 s) | 2.9 s (1.7–10.4 s) |
| Faster in the same round | 9 of 10 rounds | 1 of 10 |

**How to read it:**

- Both are **correct**: exactly 10 transfers, the balance never goes negative, and the ledger reconciles.
- Optimistic told 9–13 clients "busy, retry" when a definite answer ("insufficient balance") existed.
- **Retries ≈ successes × (concurrent attempts − 1).** The pool of 10 connections caps concurrency at 10, so each win
  invalidates the other 9 attempts in flight: 10 × 9 = 90.
- Optimistic wastes work: every losing attempt does its full set of reads and writes, then throws it all away.

Reproduce: `./mvnw test -Dtest=LockingStrategyComparisonTest` (Docker required; look for lines starting with `COMPARE`).

## 8.6 When each strategy wins

| | Pessimistic | Optimistic |
|---|---|---|
| Best for | **High contention**: many transfers on one wallet (merchant, payroll account) | **Low contention**: transfers spread over many wallets |
| Behaviour under contention | Orderly queue, no wasted work | Retry storms, wasted work, spurious 409s |
| Behaviour with no contention | Takes locks it didn't need (cheap in Postgres) | No waiting at all |
| Cost | Holds a DB connection while waiting, so a hot wallet's throughput = 1 / lock-hold-time | Wasted attempts, client-visible conflicts |
| Deadlock risk | Yes, mitigated by lock ordering | Only at commit, mitigated by load ordering |
| Failure mode | Lock waits pile up and exhaust the connection pool | Starvation: an unlucky transfer can lose every time |

**Interview-ready summary:** "Pessimistic locking serialises access up front and wins when conflicts are likely. Optimistic
locking detects conflicts at commit and wins when conflicts are rare. For a payments ledger with hot merchant wallets, I'd default to
pessimistic, and I measured it: 9 of 10 rounds faster, with zero spurious conflicts."

## 8.7 Duplicated code: a known trade-off

`DECISIONS.md` (Day 19) accepts that the two executors duplicate steps e–k of the transfer (transaction row, ledger, balances,
completion). They differ only in how they load the wallets. The cost has already shown up: the outbox write exists only in the
optimistic executor. The revisit trigger in `DECISIONS.md` ("one strategy is chosen for production, or the shared steps change for the
first time") has now fired. See chapter 17 for the refactor.
