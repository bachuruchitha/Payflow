# PayFlow

Digital wallet and payment transfer backend (Spring Boot 4, Java 21, PostgreSQL, Flyway).

## Locking comparison: pessimistic vs optimistic

PayFlow has two interchangeable transfer strategies, so the tradeoff can be measured instead of argued:
`TransferExecutor` (pessimistic) and `OptimisticTransferExecutor` + `OptimisticTransferService` (optimistic).

### Pessimistic (`SELECT ... FOR UPDATE`)

Each transfer locks both wallet rows before reading the balance, so concurrent transfers on the same wallet wait in
line and each one checks the true, up-to-date balance: 50 simultaneous transfers can't all spend the same 1000.
The cost: transfers on one wallet run strictly one at a time, each holding a database connection while it waits,
so a hot wallet's throughput is capped by how long each transaction holds the lock.

### Optimistic (`@Version` + retry)

Wallets are read without locks, and at commit Hibernate writes each one with `UPDATE ... WHERE id = ? AND version = ?`.
If another transfer committed first, that update matches 0 rows, the attempt rolls back, and `OptimisticTransferService`
retries it in a fresh transaction (up to 3 attempts, then `409 TRANSFER_CONFLICT`).
The cost: every losing attempt does all its work before being thrown away, and under contention each commit makes every
other attempt running at that moment fail, so a client can be told "busy, retry" when a definite answer existed.

### When each wins

Pessimistic wins under **high contention** (many transfers on one wallet, such as a merchant or payroll account): they
have to queue anyway, and the lock makes the queue orderly with no wasted work.
Optimistic wins under **low contention** (transfers spread across many wallets): conflicts are rare, so almost nothing
is retried and no request ever waits behind another's lock.

Measured (Day 19): 50 threads each sending 100 from one wallet that funds exactly 10, with the default
connection pool of 10, 10 rounds per strategy:

| | Pessimistic | Optimistic (3 attempts) |
|---|---|---|
| Transfers completed | 10 every round | 10 every round |
| "Insufficient balance" | 40 | 27–31 |
| Gave up (`409 TRANSFER_CONFLICT`) | 0 | 9–13 |
| Retries | 0 | exactly 90 every round (10 wins × 9 losers) |
| Time for all 50 requests, median (range) | 1.9 s (1.4–3.1 s) | 2.9 s (1.7–10.4 s) |
| Faster in the same round | 9 of 10 rounds | 1 of 10 |

Retries under contention ≈ successes × (transfers running at once − 1), and the connection pool size limits how
many run at once. Absolute times reflect Docker Desktop on Windows; compare the columns, not the numbers.
Reproduce with `./mvnw test -Dtest=LockingStrategyComparisonTest` (Docker required).

### Deadlock prevention

Both executors touch the two wallets in one global order, **ascending wallet id**, whatever the transfer's direction:
the pessimistic executor for its `FOR UPDATE` locks, and the optimistic one for the order it loads the wallets,
because Hibernate sends the lock-taking `UPDATE`s at commit in load order.
A deadlock needs a cycle (A→B holds A and waits for B while B→A holds B and waits for A), and with one fixed order
every transaction only waits for a lock later in that order than any it already holds, so a cycle can't form.
