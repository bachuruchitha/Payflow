# Decisions

Tradeoffs we chose on purpose, and what it would take to revisit them.

## 2026-09-13 (Day 19): Duplicated transfer logic between the two executors

**Decision:** Accepted duplication between the two executors deliberately, to keep both strategies side-by-side for the interview comparison.

- `TransferExecutor` (pessimistic: `SELECT ... FOR UPDATE`) and `OptimisticTransferExecutor` (`@Version` check at commit)
  differ only in how they load the two wallets. The transaction row, the ledger entries and the balance updates are copied.
- **Cost:** any change to the transfer steps (a new ledger field, a new status, a fee) has to be made in both classes, or they drift apart.
- **Revisit when:** one strategy is chosen for production, or the shared steps change for the first time. Then either delete the
  losing executor, or extract the shared steps into one method that both executors call after loading their wallets.
