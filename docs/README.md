# PayFlow Developer Guide

This folder is a guide to the whole PayFlow codebase. Each chapter covers one module, feature or concept.
Each chapter explains **what** the code does, **how** it does it (with file and line references), and **why** it was built
that way.

> Snapshot: written against the working tree on 2026-09-21 (branch `master`, last commit `1259491 till day 26`, plus the
> uncommitted changes that send `POST /api/transfers` through the optimistic executor and move to Jackson 3 imports).

---

## Reading order

If you are new to the project, read the chapters in this order. Each one assumes the ones before it.

| # | Chapter | What you'll learn |
|---|---|---|
| 01 | [Overview & Architecture](01-overview-and-architecture.md) | What PayFlow is, the tech stack, the layers, how a request travels through the code |
| 02 | [Setup, Configuration & Running](02-setup-configuration-running.md) | Docker services, `application.yaml` line by line, starting the app, a curl walkthrough |
| 03 | [Database Schema & Migrations](03-database-schema-and-migrations.md) | Every table, every Flyway migration V1–V9, how JPA entities map to them |
| 04 | [Authentication & Security](04-authentication-and-security.md) | Registration, BCrypt, login, JWT creation/validation, the security filter chain |
| 05 | [Wallets & Top-Up](05-wallets-and-topup.md) | Wallet creation, reading your wallet, adding money |
| 06 | [Double-Entry Ledger & Reconciliation](06-ledger-and-reconciliation.md) | Why a balance is stored twice, and how the two copies are checked against each other |
| 07 | [Transfers: End-to-End Flow](07-transfers.md) | The most important flow: every step from HTTP request to committed money movement |
| 08 | [Concurrency: Pessimistic vs Optimistic Locking](08-concurrency-and-locking.md) | Row locks, `@Version`, retries, deadlock prevention, the measured comparison |
| 09 | [Idempotency](09-idempotency.md) | How "pressing Pay twice" is made safe with `Idempotency-Key` |
| 10 | [Rate Limiting (Redis + Lua Token Bucket)](10-rate-limiting.md) | The token bucket algorithm, the Lua script, fail-open policy, `Retry-After` |
| 11 | [Balance Cache (Redis)](11-balance-cache.md) | The derived-balance cache and the "evict only after commit" rule |
| 12 | [Events: Outbox, Kafka & Notifications](12-events-outbox-kafka.md) | Transactional outbox, the scheduled publisher, the idempotent Kafka consumer |
| 13 | [Transaction History & Pagination](13-transaction-history.md) | `GET /api/transactions`, direction/counterparty mapping, `Pageable` |
| 14 | [Error Handling](14-error-handling.md) | Every custom exception, how it maps to an HTTP status and error code |
| 15 | [API Reference](15-api-reference.md) | Every endpoint with request/response examples |
| 16 | [Testing](16-testing.md) | Every test class, what it proves, what infrastructure it needs |
| 17 | [Known Issues & Improvement Roadmap](17-known-issues-and-roadmap.md) | Verified bugs and gaps, ranked, with fixes |
| 18 | [File-by-File Index](18-file-index.md) | Every source file in one table, with a one-line purpose and chapter link |
| 19 | [Glossary](19-glossary.md) | Every term used in this guide (TOCTOU, outbox, idempotency, ...) |

## Quick paths

- **"I just want to run it"**: [02](02-setup-configuration-running.md), then [15](15-api-reference.md).
- **"Explain the money-safety design"**: [06](06-ledger-and-reconciliation.md), [08](08-concurrency-and-locking.md), [09](09-idempotency.md).
- **"Prepare me for an interview about this project"**: [01](01-overview-and-architecture.md), [07](07-transfers.md), [08](08-concurrency-and-locking.md), [10](10-rate-limiting.md), [12](12-events-outbox-kafka.md), [17](17-known-issues-and-roadmap.md).
- **"Where is X in the code?"**: [18](18-file-index.md).

## Other documents in the repo

| File | What it is | Relation to this guide |
|---|---|---|
| `README.md` (root) | Short project intro plus the pessimistic-vs-optimistic benchmark | Summarised and extended in chapter 08 |
| `DECISIONS.md` (root) | Architecture decision log (dated trade-offs) | Each decision is referenced from the chapter it affects |
| `PROJECT_ANALYSIS.md` (root) | An earlier analysis from 2026-09-13 (Day 20), before Redis, Kafka and rate limiting existed | Partly out of date. This guide replaces it; chapter 17 re-checks each issue it listed |
