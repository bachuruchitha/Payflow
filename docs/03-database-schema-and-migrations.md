# 03 — Database Schema & Migrations

## 3.1 How the schema is managed

- The schema is defined **only** by Flyway SQL files in `src/main/resources/db/migration/`.
- Flyway runs them in version order at startup and records each one in the `flyway_schema_history` table, with a checksum.
- Hibernate runs with `ddl-auto: validate`: it checks entities against the tables but never changes them.

**Golden rule:** never edit a migration that has already run anywhere, not even a comment. Flyway would detect the checksum
change and refuse to start. Schema changes always go in a new file, `V10__…sql`.

## 3.2 Migration history

| Version | File | What it does | Why |
|---|---|---|---|
| V1 | `V1__create_users.sql` | `users(id UUID PK, email UNIQUE NOT NULL, password_hash, created_at)` | Accounts |
| V2 | `V2__create_wallets_table.sql` | `wallets(id, user_id UNIQUE FK→users, currency VARCHAR(3), version INT DEFAULT 0, created_at)` | One wallet per user. `version` is prepared for optimistic locking from day one |
| V3 | `V3__create_ledger_entries.sql` | Adds `wallets.balance NUMERIC(19,4) DEFAULT 0`; creates `ledger_entries` with `CHECK (entry_type IN ('DEBIT','CREDIT'))` and `CHECK (amount > 0)` | Stored balance + double-entry ledger |
| V4 | `V4__create_trasactions.sql` (typo in name, can't be renamed now) | `transactions(from_wallet_id FK, to_wallet_id FK, status VARCHAR(6) CHECK IN ('PENDING','COMPLETED'), amount CHECK > 0, created_at, completed_at)` | Transfer records |
| V5 | `V5__alter_transactions.sql` | `status` → `VARCHAR(20)` | **Bug fix:** `'COMPLETED'` is 9 characters and didn't fit in `VARCHAR(6)` |
| V6 | `V6__add_idempotency_key_to_transactions.sql` | Adds `idempotency_key`: nullable → backfill with `id::text` → `NOT NULL` → `UNIQUE` | Duplicate-submission protection. Shows the safe 3-step pattern for adding a NOT NULL column to a table that already has rows. (Its first comment line still says "V5". Leave it, because changing it changes the checksum) |
| V7 | `V7__add_ledger_wallet_id_index.sql` | Index on `ledger_entries(wallet_id)` | Speeds up `SUM` over a wallet's entries (reconciliation, derived balance) |
| V8 | `V8__create_outbox_events.sql` | `outbox_events(id, is_published BOOLEAN DEFAULT FALSE, payload TEXT, transaction_id, created_at)` | Transactional outbox (chapter 12) |
| V9 | `V9__create_notifications_and_processed_events.sql` | `processed_events(transaction_id PK, processed_at)` and `notifications(id, transaction_id, message, created_at)` | Idempotent consumer + consumer output |

## 3.3 Tables in detail

### `users`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Generated in Java (`UUID.randomUUID()`), not by the DB |
| `email` | VARCHAR(255) UNIQUE NOT NULL | Case-sensitive: `A@x.com` and `a@x.com` are different users |
| `password_hash` | VARCHAR(255) NOT NULL | BCrypt hash (`$2a$10$…`), never the raw password |
| `created_at` | TIMESTAMPTZ | Set in Java (`Instant.now()`) |

### `wallets`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `user_id` | UUID UNIQUE FK→users | UNIQUE = one wallet per user, and gives an index for `findByUserId` |
| `currency` | VARCHAR(3) | Always `"USD"` today (hard-coded in `UserService.register`) |
| `version` | INTEGER DEFAULT 0 | JPA `@Version`. Hibernate increments it on every update and uses it for optimistic concurrency |
| `balance` | NUMERIC(19,4) DEFAULT 0 | **Source of truth** for money decisions (chapter 06) |
| `created_at` | TIMESTAMPTZ | |

### `ledger_entries` (append-only)
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `transaction_id` | UUID, nullable, **no FK** | Links to `transactions.id` for transfers; `NULL` for top-ups |
| `wallet_id` | UUID FK→wallets, indexed (V7) | |
| `entry_type` | VARCHAR(6) CHECK DEBIT/CREDIT | |
| `amount` | NUMERIC(19,4) CHECK > 0 | Always positive; the direction comes from `entry_type` |
| `created_at` | TIMESTAMPTZ | |

Rows are only ever inserted. No code updates or deletes them, which is what makes the ledger an audit trail.

### `transactions`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `from_wallet_id` / `to_wallet_id` | UUID FK→wallets | **Not indexed**: history queries scan the table (chapter 17) |
| `status` | VARCHAR(20) CHECK PENDING/COMPLETED | In practice always `COMPLETED` once committed. `PENDING` exists only inside the transaction |
| `amount` | NUMERIC(19,4) CHECK > 0 | |
| `created_at`, `completed_at` | TIMESTAMPTZ | |
| `idempotency_key` | VARCHAR(255) NOT NULL **UNIQUE** (global) | The DB-level guarantee against double execution (chapter 09) |

### `outbox_events`
| Column | Notes |
|---|---|
| `id` | UUID PK |
| `transaction_id` | The transfer the event describes; also used as the Kafka message key |
| `payload` | JSON text of `TransferEventPayload` |
| `is_published` | `false` until the publisher gets an ack from Kafka. **Not indexed**, and published rows are never deleted |
| `created_at` | DB default `now()`. The entity marks it `insertable=false, updatable=false`, so the DB fills it |

### `processed_events`
| Column | Notes |
|---|---|
| `transaction_id` | **PK**, so a second insert for the same event fails: a DB-enforced "already handled" marker |
| `processed_at` | DB default |

### `notifications`
| Column | Notes |
|---|---|
| `id`, `transaction_id`, `message`, `created_at` | The consumer's output: `"Transfer <id> completed"`. Not linked to a user yet |

## 3.4 Money types: why `NUMERIC(19,4)` and `BigDecimal`

- Floating point (`double`) can't represent 0.1 exactly. Money must use exact decimals: `NUMERIC` in SQL and `BigDecimal` in Java.
- `(19,4)` = 19 significant digits, 4 after the decimal point.
- **Compare with `compareTo`, never `equals`**: `new BigDecimal("10.00").equals(new BigDecimal("10.0000"))` is `false`
  (different scale), but `compareTo` returns `0`. The code does this correctly everywhere (`ReconciliationService`, the executors, the tests).
- **Gap:** PostgreSQL silently **rounds** inputs to 4 decimals, and nothing in the API limits decimals. See chapter 17, issue #1.

## 3.5 Entity ↔ table mapping

| Entity (`entity/`) | Table | Notable mapping details |
|---|---|---|
| `User` | `users` | Plain columns; `protected` no-arg constructor required by JPA |
| `Wallet` | `wallets` | `@Version Integer version` gives optimistic locking. `setBalance` is the only setter |
| `LedgerEntry` | `ledger_entries` | `@Enumerated(EnumType.STRING) EntryType`, stored as text so it matches the CHECK constraint |
| `Transaction` | `transactions` | Java field `transactionId` maps to column `id`. `markCompleted()` sets status + `completedAt` |
| `OutboxEvent` | `outbox_events` | `createdAt` is DB-generated (`insertable=false, updatable=false`) |
| `ProcessedEvent` | `processed_events` | `@Id` is the `transaction_id` itself |
| `Notification` | `notifications` | DB-generated `createdAt` |
| `EntryType` enum | — | `DEBIT`, `CREDIT` |
| `TransactionStatus` enum | — | `PENDING`, `COMPLETED` |

### A subtle JPA detail: assigned UUIDs and `save()`

All ids are assigned in Java before saving. Spring Data's `save()` decides between `persist` (insert) and `merge` by asking
"is this entity new?":

- **`Wallet`** has a `@Version` field that is `null` on a new object, so Spring Data treats it as new and calls `persist`: one INSERT.
- **`User`, `Transaction`, `LedgerEntry`, …** have no version field and a non-null id, so Spring Data assumes they
  **already exist** and calls `merge`. That issues a `SELECT` first, finds nothing, then INSERTs. It works, but every insert costs
  an extra query. It also means `save()` returns a **different instance** than the one passed in, which is why the executors
  keep the return value of `transactionRepository.save(...)` and call `markCompleted()` on *that* object.
  (Fix, if wanted: implement `Persistable<UUID>` on those entities.)

## 3.6 Repositories (data access)

| Repository | Custom methods | Notes |
|---|---|---|
| `UserRepository` | `existsByEmail`, `findByEmail` | Derived queries |
| `WalletRepository` | `findByUserId`; `findWalletIdByUserId` (JPQL scalar projection); `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) | `findWalletIdByUserId` loads only the id, so the *first* time the wallet row is read is the locked read (chapter 08) |
| `LedgerEntryRepository` | `computeBalanceFromLedger(walletId)` | `SUM(CASE WHEN CREDIT THEN amount ELSE -amount END)` with `COALESCE(…, 0)` for wallets with no entries |
| `TransactionRepository` | `findByFromWalletIdOrToWalletId(from, to, Pageable)`, `findByIdempotencyKey` | History + idempotency lookup |
| `OutboxEventRepository` | `findTop100ByIsPublishedFalseOrderByCreatedAtAsc` | Batch of 100 oldest unpublished |
| `ProcessedEventRepository` | `existsByTransactionId` | Consumer dedup check |
| `NotificationRepository` | `existsByTransactionId` | Currently unused |

## 3.7 Handy SQL for exploring

```sql
-- Stored vs ledger-derived balance for every wallet (should always match)
select w.id, w.balance,
       coalesce(sum(case when l.entry_type='CREDIT' then l.amount else -l.amount end),0) as derived
from wallets w left join ledger_entries l on l.wallet_id = w.id
group by w.id, w.balance;

-- Total money in the system = total of top-ups (transfers only move money around)
select sum(balance) from wallets;
select sum(amount) from ledger_entries where transaction_id is null;

-- Events not yet published to Kafka
select count(*) from outbox_events where not is_published;
```
