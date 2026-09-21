# 18 — File-by-File Index

Every file in the project, what it does, and where it's explained. Paths are relative to `src/main/java/com/payflow/payflow/`
unless noted.

## Root & build

| File | Purpose | Chapter |
|---|---|---|
| `pom.xml` | Maven build: Spring Boot 4.1.0 parent, Java 21, starters (webmvc, data-jpa, validation, security, flyway, data-redis, kafka), JJWT 0.12.6, springdoc 3.0.3, Testcontainers | 01 |
| `docker-compose.yml` | Local PostgreSQL 16, Redis 7.4, Kafka 3.8 (KRaft) | 02 |
| `mvnw`, `mvnw.cmd`, `.mvn/` | Maven wrapper | 02 |
| `README.md` | Project intro + locking benchmark | 08 |
| `DECISIONS.md` | Architecture decision log | 06, 08, 10, 11 |
| `PROJECT_ANALYSIS.md` | Earlier (Day 20) analysis, partly outdated | 17 |
| `HELP.md` | Spring Initializr boilerplate (ignored by `.gitignore`) | — |

## Resources (`src/main/resources/`)

| File | Purpose | Chapter |
|---|---|---|
| `application.yaml` | DB, Redis (1 s timeouts), JPA validate, Flyway, JWT, rate-limit config | 02 |
| `db/migration/V1__create_users.sql` | `users` table | 03 |
| `db/migration/V2__create_wallets_table.sql` | `wallets` with `version` | 03 |
| `db/migration/V3__create_ledger_entries.sql` | `wallets.balance` + `ledger_entries` | 03, 06 |
| `db/migration/V4__create_trasactions.sql` | `transactions` | 03 |
| `db/migration/V5__alter_transactions.sql` | Widen `status` to fit `COMPLETED` | 03 |
| `db/migration/V6__add_idempotency_key_to_transactions.sql` | Idempotency key, safe backfill, UNIQUE | 03, 09 |
| `db/migration/V7__add_ledger_wallet_id_index.sql` | Index for ledger sums | 03 |
| `db/migration/V8__create_outbox_events.sql` | Outbox table | 03, 12 |
| `db/migration/V9__create_notifications_and_processed_events.sql` | Consumer tables | 03, 12 |
| `scripts/token_bucket.lua` | Atomic token bucket executed in Redis | 10 |

## Application entry point & configuration

| File | Purpose | Chapter |
|---|---|---|
| `PayflowApplication.java` | `main`; `@EnableScheduling` for the outbox poller | 01, 12 |
| `configuration/SecurityConfig.java` | BCrypt bean; filter chain: public paths, stateless, JWT filter | 04 |
| `configuration/OpenApiConfig.java` | Swagger bearer-JWT scheme | 04 |
| `configuration/RateLimiterConfig.java` | Lua script bean (EVALSHA), `Clock` bean | 10 |

## Security

| File | Purpose | Chapter |
|---|---|---|
| `security/JwtService.java` | Sign (HS256, sub=userId, 1 h) and verify JWTs | 04 |
| `security/JwtAuthenticationFilter.java` | Reads the Bearer token, sets the principal to the `UUID` userId | 04 |

## Controllers

| File | Endpoints | Chapter |
|---|---|---|
| `controller/UserController.java` | `POST /api/users/register` | 04 |
| `controller/AuthController.java` | `POST /api/auth/login` | 04 |
| `controller/WalletController.java` | `GET /api/wallets/me`, `POST /api/wallets/me/topup` | 05 |
| `controller/TransferController.java` | `POST /api/transfers` (rate limit → optimistic transfer), `GET /api/transactions` | 07, 10, 13 |
| `health/HealthController.java` | `GET /health` → `OK` | 15 |

## Services

| File | Purpose | Chapter |
|---|---|---|
| `service/UserService.java` | Register: user + USD wallet in one transaction | 04 |
| `service/AuthService.java` | Login: BCrypt check → JWT | 04 |
| `service/WalletService.java` | Get wallet, top-up (ledger + balance), derived balance via cache | 05, 11 |
| `service/TransferService.java` | Idempotency wrapper for both strategies; history + DTO mapping | 07, 09, 13 |
| `service/TransferExecutor.java` | **Pessimistic** transfer: `FOR UPDATE` in id order | 07, 08 |
| `service/OptimisticTransferService.java` | Retry loop (3 attempts, jitter), not transactional | 07, 08 |
| `service/OptimisticTransferExecutor.java` | **Optimistic** transfer attempt: `@Version`, id-ordered loads, writes the outbox | 07, 08, 12 |
| `service/ReconciliationService.java` | Stored vs ledger balance comparison | 06 |
| `service/BalanceCache.java` | Redis derived-balance cache, evict-after-commit | 11 |
| `service/RateLimiter.java` | Runs the token bucket script, fail-open, `Retry-After` | 10 |
| `service/RateLimitResult.java` | `(allowed, tokensLeft)` record | 10 |
| `service/OutboxPublisher.java` | `@Scheduled` relay: outbox → Kafka | 12 |
| `service/TransactionEventConsumer.java` | `@KafkaListener`: idempotent notification writer | 12 |

## Entities & enums

| File | Table | Chapter |
|---|---|---|
| `entity/User.java` | `users` | 03 |
| `entity/Wallet.java` | `wallets` (`@Version`) | 03, 08 |
| `entity/LedgerEntry.java` | `ledger_entries` | 03, 06 |
| `entity/Transaction.java` | `transactions` | 03, 07 |
| `entity/OutboxEvent.java` | `outbox_events` | 12 |
| `entity/ProcessedEvent.java` | `processed_events` | 12 |
| `entity/Notification.java` | `notifications` | 12 |
| `entity/EntryType.java` | `DEBIT` / `CREDIT` | 06 |
| `entity/TransactionStatus.java` | `PENDING` / `COMPLETED` | 07 |

## Repositories

| File | Key methods | Chapter |
|---|---|---|
| `repository/UserRepository.java` | `existsByEmail`, `findByEmail` | 04 |
| `repository/WalletRepository.java` | `findByUserId`, `findWalletIdByUserId`, `findByIdForUpdate` | 05, 08 |
| `repository/LedgerEntryRepository.java` | `computeBalanceFromLedger` | 06 |
| `repository/TransactionRepository.java` | `findByFromWalletIdOrToWalletId`, `findByIdempotencyKey` | 09, 13 |
| `repository/OutboxEventRepository.java` | `findTop100ByIsPublishedFalseOrderByCreatedAtAsc` | 12 |
| `repository/ProcessedEventRepository.java` | `existsByTransactionId` | 12 |
| `repository/NotificationRepository.java` | `existsByTransactionId` (unused) | 12 |

## DTOs

| File | Used for | Chapter |
|---|---|---|
| `dto/RegisterRequest.java` / `RegisterResponse.java` | Register | 04 |
| `dto/LoginRequest.java` / `LoginResponse.java` | Login | 04 |
| `dto/WalletResponse.java` | Wallet endpoints (no `version`) | 05 |
| `dto/TopUpRequest.java` | Top-up | 05 |
| `dto/TransferRequest.java` / `TransferResponse.java` | Transfer | 07 |
| `dto/TransactionHistoryResponse.java` + `Direction.java` | History | 13 |
| `dto/TransferEventPayload.java` | Kafka/outbox JSON | 12 |
| `dto/ErrorResponse.java` | All error bodies | 14 |

## Exceptions

| File | HTTP | Chapter |
|---|---|---|
| `exception/GlobalExceptionHandler.java` | Maps everything | 14 |
| `exception/InvalidCredentialsException.java` | 401 | 04, 14 |
| `exception/DuplicateEmailException.java` | 409 | 04, 14 |
| `exception/WalletNotFoundException.java` | 404 | 14 |
| `exception/InsufficientBalanceException.java` | 409 | 07, 14 |
| `exception/SelfTransferNotAllowedException.java` | 400 | 07, 14 |
| `exception/TransferConflictException.java` | 409 | 08, 14 |
| `exception/TooManyRequestsException.java` | 429 | 10, 14 |

## Tests (`src/test/java/com/payflow/payflow/`)

| File | Proves | Chapter |
|---|---|---|
| `PayflowApplicationTests.java` | Context starts | 16 |
| `service/ConcurrentTransferTest.java` | Pessimistic lock prevents overdraft (50 threads) | 08, 16 |
| `service/LockingStrategyComparisonTest.java` | Benchmark, both strategies | 08, 16 |
| `service/LedgerReconcilationTest.java` | Conservation + reconciliation (weak, see 17 #9) | 06, 16 |
| `service/TopUpBalanceConsistencyTest.java` | Top-up keeps ledger/balance/cache aligned | 11, 16 |
| `service/RateLimiterTest.java` | Token bucket, refill, fail-open, atomicity | 10, 16 |
