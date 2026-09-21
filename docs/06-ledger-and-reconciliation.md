# 06 — Double-Entry Ledger & Reconciliation

**Files:** `entity/LedgerEntry.java`, `entity/EntryType.java`, `repository/LedgerEntryRepository.java`,
`service/ReconciliationService.java`, plus the write paths in `WalletService.topUp`, `TransferExecutor`, `OptimisticTransferExecutor`.
Decision record: `DECISIONS.md` → "The stored `wallets.balance` column is the source of truth; the ledger is the audit trail".

## 6.1 The concept: double-entry bookkeeping

Accountants have recorded money this way for 500 years: **every movement is written twice**, as a debit on one account and
an equal credit on another. Money is never created or destroyed by a transfer, only moved.

A transfer of 100 from Alice to Bob in PayFlow:

| ledger_entries | wallet | entry_type | amount | transaction_id |
|---|---|---|---|---|
| row 1 | Alice | DEBIT | 100.0000 | T1 |
| row 2 | Bob | CREDIT | 100.0000 | T1 |

A top-up of 500 into Alice (money entering the system from outside):

| wallet | entry_type | amount | transaction_id |
|---|---|---|---|
| Alice | CREDIT | 500.0000 | NULL |

A wallet's balance can always be **derived** by summing its entries:

```
balance = Σ CREDIT amounts − Σ DEBIT amounts
```

That's exactly what `LedgerEntryRepository.computeBalanceFromLedger` does in JPQL:

```sql
SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
FROM LedgerEntry e WHERE e.walletId = :walletId
```

`COALESCE(…, 0)` makes a wallet with no entries return 0 instead of `NULL`. Index `idx_ledger_entries_wallet_id` (V7) keeps
this query fast.

## 6.2 Two balances: which one is the truth?

PayFlow stores a balance **twice**:

1. **`wallets.balance`**: one number per wallet, updated on every write.
2. **The ledger sum**: recomputed from the append-only `ledger_entries`.

The decision (`DECISIONS.md`, Day 21): **`wallets.balance` is authoritative for every money decision. The ledger is the
independent audit trail it's reconciled against.**

### Why the stored column wins

The overdraft check ("does the sender have enough?") must be **serialised** against concurrent transfers. Two transfers must
never both read "1000 available" and both spend it.

- With the stored column, you lock **one row** (`SELECT … FOR UPDATE`) or version-check **one row** (`@Version`).
  That's cheap and exact.
- With a derived `SUM`, there's no single row to lock. You'd have to lock every ledger row of the wallet (and block new
  inserts, which needs predicate locks or SERIALIZABLE isolation), or accept a **time-of-check to time-of-use (TOCTOU)
  race** on the very check that prevents overdraft. A *cached* sum (chapter 11) would be worse still.

### Why keep the ledger then

- It is **append-only**, so history can't be silently rewritten. An auditor can recompute every balance independently.
- If a bug in some write path forgets to update the balance (or updates it wrongly), the ledger exposes the drift.
  `TransferExecutor` even has a comment `// FIX (Bug 1): was missing entirely`. The balance update was once forgotten, and
  this invariant is what catches that class of bug.

### The invariant every write path owes

> **Append the ledger entries AND update the stored balance, in the same database transaction.**

| Write path | Ledger | Stored balance | Same transaction? |
|---|---|---|---|
| `WalletService.topUp` | 1 CREDIT | `+amount` | ✅ `@Transactional` |
| `TransferExecutor.executeTransfer` | DEBIT + CREDIT | sender `−`, receiver `+` | ✅ `@Transactional` |
| `OptimisticTransferExecutor.executeTransfer` | DEBIT + CREDIT | sender `−`, receiver `+` | ✅ `@Transactional` |

Because both writes share a transaction, a crash or exception rolls back **both**. They can never disagree because of a partial write.

## 6.3 Reconciliation: `ReconciliationService`

```java
@Transactional(readOnly = true)
public List<UUID> reconcile(List<Wallet> wallets) {
    for each wallet:
        stored  = wallet.getBalance();
        derived = ledgerEntryRepository.computeBalanceFromLedger(wallet.getId());
        if (stored.compareTo(derived) != 0) mismatches.add(wallet.getId());   // compareTo, not equals
    return mismatches;   // empty = healthy
}
```

- An **empty list means every wallet reconciles**.
- `compareTo` is used because `100.00` and `100.0000` are *equal amounts* with *different scales*. `equals` would report a false mismatch.
- `readOnly = true` tells Hibernate to skip dirty checking and lets the driver/DB optimise.
- **The caller passes the wallets in.** Nothing in the app calls it on a schedule or over HTTP yet; only the tests use it.
  A production version would be a nightly `@Scheduled` job over all wallets, raising an alert on any mismatch.

### A second, global invariant: conservation of money

Transfers only move money, so:

```
Σ all wallet balances  ==  Σ all top-ups
```

`LedgerReconciliationTest` checks a version of this: the total across its wallets before and after 100 random transfers must be
identical (chapter 16 explains a weakness in that test).

## 6.4 What reconciliation would catch, and what it wouldn't

| Bug | Caught? |
|---|---|
| Write path updates balance but forgets ledger entries (or vice versa) | ✅ mismatch |
| Lost update: two transfers both subtract from the same stale balance | ✅ stored ≠ ledger (the ledger has both debits, the balance reflects only one) |
| Rounding difference between ledger and balance (chapter 17, issue #1) | ✅ mismatch |
| A transfer to the *wrong* wallet, written consistently to both | ❌ both views agree, just wrongly |
| A ledger entry written with the wrong amount *and* the same wrong balance change | ❌ consistent but wrong |

Reconciliation proves the **two records agree with each other**, not that the business intent was right.
