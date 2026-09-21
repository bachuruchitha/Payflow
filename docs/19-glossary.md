# 19 — Glossary

| Term | Meaning in PayFlow | Chapter |
|---|---|---|
| **ACID transaction** | A group of DB writes that commit all together or not at all. Every money movement is one | 06, 07 |
| **@Transactional** | Spring annotation that wraps a method call (through a proxy) in a DB transaction; commits on return, rolls back on `RuntimeException` | 07 |
| **Proxy / self-invocation** | Spring applies `@Transactional` via a wrapper object. Calling a method on `this` bypasses the wrapper, so the method gets no transaction. That's why the retry loop is a separate bean | 07 |
| **Persistence context** | Hibernate's per-transaction cache of loaded entities. Changes to managed entities are written automatically at commit (**dirty checking**) | 03, 07 |
| **Flush** | The moment Hibernate sends pending INSERT/UPDATEs to the DB (at commit, or before certain queries) | 07 |
| **Double-entry ledger** | Every transfer recorded as a DEBIT on one wallet and an equal CREDIT on another | 06 |
| **Append-only** | Rows are only inserted, never updated or deleted (ledger entries) | 06 |
| **Source of truth** | The value decisions are based on: `wallets.balance` | 06 |
| **Derived balance** | The balance recomputed by summing ledger entries | 06, 11 |
| **Reconciliation** | Comparing stored and derived balances to detect drift | 06 |
| **Race condition** | The result depends on the timing of concurrent operations | 08 |
| **TOCTOU** | Time-of-check to time-of-use: the checked value changes before it's used (e.g. balance read, then spent by someone else) | 08 |
| **Lost update** | Two transactions read the same value and both write back, so one change disappears | 08 |
| **Pessimistic locking** | Lock the row before reading (`SELECT … FOR UPDATE`); others wait | 08 |
| **Optimistic locking** | Don't lock; detect a concurrent change at write time via a version number and retry | 08 |
| **@Version** | JPA field Hibernate increments on each update and checks with `WHERE version = ?` | 08 |
| **Deadlock** | Two transactions each hold a lock the other needs; neither can proceed | 08 |
| **Lock ordering** | Always acquire locks in one global order (ascending wallet id) so deadlocks can't form | 08 |
| **Backoff with jitter** | Waiting a random time before retrying, so colliding clients don't retry in lockstep | 07, 08 |
| **Hot row / contention** | Many concurrent transactions targeting the same row (e.g. a merchant wallet) | 08 |
| **Idempotency** | Doing an operation twice has the same effect as doing it once | 09 |
| **Idempotency-Key** | Client-generated unique id per logical payment, sent as a header | 09 |
| **Replay** | Returning the stored result of an earlier request with the same key instead of executing again | 09 |
| **Unique constraint** | A DB rule that no two rows share a value. The final guard for idempotency and dedup | 03, 09, 12 |
| **Rate limiting** | Capping how often a client can call an endpoint | 10 |
| **Token bucket** | Rate-limit algorithm: a bucket of tokens refilled at a steady rate; each request spends one | 10 |
| **Burst / sustained rate** | Max back-to-back requests (capacity = 10) vs long-run rate (refill = 10/min) | 10 |
| **Lazy refill** | Computing the refill only when a request arrives, from the elapsed time | 10 |
| **Lua script (Redis)** | Code Redis executes atomically; used to make the bucket's read-modify-write race-free | 10 |
| **EVALSHA** | Run a cached Redis script by its SHA1 digest instead of sending the whole body | 10 |
| **Fail-open / fail-closed** | When a dependency fails: allow the request (open) or reject it (closed). The rate limiter fails open | 10 |
| **Retry-After** | HTTP header telling the client how many seconds to wait before retrying | 10 |
| **Cache-aside** | App reads the cache; on a miss it loads from the DB and fills the cache | 11 |
| **TTL** | Time-to-live: a cache entry's automatic expiry (5 s for balances) | 11 |
| **Evict after commit** | Delete cache entries only once the DB transaction has committed (`TransactionSynchronization.afterCommit`) | 11 |
| **Dual-write problem** | Writing to two systems (DB + Kafka) can't be made atomic, so one can succeed while the other fails | 12 |
| **Transactional outbox** | Write the event to a DB table in the same transaction as the business change; relay it to the broker later | 12 |
| **At-least-once delivery** | Messages are never lost but may arrive more than once | 12 |
| **Idempotent consumer** | A consumer that records processed message ids so duplicates have no effect | 12 |
| **Effectively-once** | At-least-once delivery + idempotent consumer = each event's effect happens exactly once | 12 |
| **Kafka topic / partition / key** | A named stream (`transaction-events`); partitions split it; messages with the same key go to the same partition, in order | 12 |
| **Consumer group** | Instances sharing a group id (`notification-service`) split the partitions, so each message is handled once per group | 12 |
| **KRaft** | Kafka's built-in consensus mode that replaces ZooKeeper | 02 |
| **Dead-letter topic (DLT)** | Where messages that keep failing are sent for later inspection (not configured yet) | 12, 17 |
| **JWT** | JSON Web Token: signed, base64url `header.payload.signature` carrying claims (`sub`, `iat`, `exp`) | 04 |
| **HS256** | HMAC-SHA256 signature using one shared secret key | 04 |
| **Bearer token** | "Whoever bears this token is authenticated": `Authorization: Bearer <jwt>` | 04 |
| **Stateless auth** | No server-side session; every request carries its own proof of identity | 04 |
| **BCrypt** | Slow, salted password-hashing algorithm | 04 |
| **Salt** | Random data mixed into each password hash so identical passwords hash differently | 04 |
| **User enumeration** | Learning which accounts exist from differing error messages; prevented by one generic login error | 04 |
| **CSRF** | Cross-site request forgery; relevant for cookie auth, not for header-based JWT auth (disabled here) | 04 |
| **Principal** | The authenticated identity in Spring Security; here the user's `UUID` | 04 |
| **DTO** | Data Transfer Object: the shape sent over the API, separate from entities | 01 |
| **Flyway migration** | Versioned SQL file (`V<n>__desc.sql`) applied once, in order, and checksummed | 03 |
| **`ddl-auto: validate`** | Hibernate checks entities match the schema but never changes it | 02, 03 |
| **Testcontainers** | Library that starts real throwaway Docker containers (Postgres) for tests | 16 |
| **CountDownLatch** | Java concurrency helper used as a "start line" to release many threads at once | 16 |
| **Offset pagination** | `LIMIT/OFFSET` paging (`page`, `size`); simple but slow for deep pages | 13 |
| **Keyset pagination** | Paging by "after this (created_at, id)", which is stable and fast | 13 |
