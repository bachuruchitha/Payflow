# 05 — Wallets & Top-Up

**Files:** `controller/WalletController.java`, `service/WalletService.java`, `entity/Wallet.java`,
`repository/WalletRepository.java`, `dto/WalletResponse.java`, `dto/TopUpRequest.java`

## 5.1 Wallet lifecycle

| Event | Where | What happens |
|---|---|---|
| Created | `UserService.register` | `new Wallet(UUID.randomUUID(), userId, "USD")`: balance `0`, version `null` (becomes `0` on insert) |
| Read | `GET /api/wallets/me` | Looked up by the authenticated user id |
| Credited by top-up | `POST /api/wallets/me/topup` | CREDIT ledger entry + balance update |
| Debited/credited by transfer | `POST /api/transfers` | Chapter 07 |
| Deleted/closed | — | Not supported |

A user has exactly one wallet (`wallets.user_id UNIQUE`). The currency is always USD, and there's no currency conversion logic.

## 5.2 `GET /api/wallets/me`

```java
Wallet wallet = walletService.getWallet(userId);   // findByUserId or WalletNotFoundException (404)
return new WalletResponse(id, userId, currency, createdAt, balance);
```

- Returns the **stored** `wallets.balance` column, the authoritative balance (see chapter 06 for why not the ledger sum).
- `WalletResponse` deliberately **omits `version`**. It's an internal concurrency detail, and exposing it would invite clients
  to depend on it.

Example response:

```json
{
  "id": "3f1b…",
  "userId": "7c1e…",
  "currency": "USD",
  "createdAt": "2026-09-21T10:15:30Z",
  "balance": 400.0000
}
```

## 5.3 `POST /api/wallets/me/topup`

Request: `{"amount": 500}`. `TopUpRequest` validates `@NotNull @Positive BigDecimal amount`.

`WalletService.topUp` (`@Transactional`):

```java
Wallet wallet = walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new);
ledgerEntryRepository.save(new LedgerEntry(id, null /* no transaction */, wallet.getId(), CREDIT, amount));
wallet.setBalance(wallet.getBalance().add(amount));
walletRepository.save(wallet);                      // redundant: the entity is managed, dirty checking would flush it anyway
balanceCache.evictAfterCommit(wallet.getId());      // chapter 11
return wallet;
```

What this upholds:

- **The ledger invariant:** one CREDIT entry and the balance change are written **in the same transaction**. Either both
  commit or neither does (chapter 06).
- **Top-ups have `transaction_id = NULL`** in the ledger. They're money *entering* the system from outside, not a transfer
  between two wallets, so there is no counterpart entry.
- **Cache eviction happens after commit**, so the derived-balance cache can never keep a pre-top-up value (chapter 11).

### Concurrency behaviour of top-up

Top-up reads the wallet **without a lock** (`findByUserId`, not `findByIdForUpdate`). It's still **safe** (no lost update)
because `Wallet` has `@Version`: Hibernate writes `UPDATE wallets SET balance=?, version=version+1 WHERE id=? AND version=?`.

- Two concurrent top-ups on the same wallet: one commits, the other's UPDATE matches 0 rows →
  `ObjectOptimisticLockingFailureException`.
- A top-up racing a pessimistic transfer: the transfer holds `FOR UPDATE`, the top-up's UPDATE blocks until the transfer commits,
  then fails the version check.

In both cases **no money is lost or created**. But the exception isn't handled or retried, so it falls through to the
catch-all handler as **500 `INTERNAL_ERROR`**. The client gets a scary error for what is really "busy, please retry". There's also
no idempotency key on top-up, so a blind client retry could credit twice. Both are listed in chapter 17.

### Security note

Top-up has no payment gateway: any authenticated user can mint any amount of money into their own wallet. That's fine for a
demo. In a real system this endpoint would be replaced by a webhook from a payment provider, or restricted to admins.

## 5.4 The derived balance (not exposed)

`WalletService.getDerivedBalance(walletId)` returns the balance **recomputed from the ledger**, cached in Redis for 5 s. It is
**not used by any endpoint**; only tests call it. Its Javadoc and `DECISIONS.md` say explicitly that it must never be used to
authorise a payment. Details in chapters 06 and 11.
