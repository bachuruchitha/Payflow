# 13 — Transaction History & Pagination

**Files:** `controller/TransferController.java` (`transactions`), `service/TransferService.java` (`getTransactions`, `mapToDto`),
`repository/TransactionRepository.java` (`findByFromWalletIdOrToWalletId`), `dto/TransactionHistoryResponse.java`, `dto/Direction.java`

## 13.1 Endpoint

```http
GET /api/transactions?page=0&size=10&sort=createdAt,desc
Authorization: Bearer <jwt>
```

`Pageable pageable` in the controller signature is filled by Spring Data's web support from the query parameters:

| Param | Meaning | Default |
|---|---|---|
| `page` | Zero-based page index | `0` |
| `size` | Items per page | `20` (capped at 2000 by Spring Data) |
| `sort` | `property,direction`. Repeatable. The property must be a **field of the `Transaction` entity** (`createdAt`, `amount`, `status`, …) | **none** |

> ⚠️ With no `sort`, the order is **undefined** (whatever order PostgreSQL returns). Clients should always pass
> `sort=createdAt,desc`. A good improvement would be a default sort via `@PageableDefault(sort = "createdAt", direction = DESC)`.
> An invalid sort property (e.g. `sort=foo`) throws `PropertyReferenceException`, which currently becomes a 500.

## 13.2 How it works

```java
public Page<TransactionHistoryResponse> getTransactions(UUID userId, Pageable pageable) {
    UUID myWalletId = walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new).getId();
    Page<Transaction> page = transactionRepository.findByFromWalletIdOrToWalletId(myWalletId, myWalletId, pageable);
    return page.map(t -> mapToDto(t, myWalletId));
}
```

1. Resolve the caller's wallet from the JWT user id. You can only ever see **your own** history.
2. The derived query `findByFromWalletIdOrToWalletId` generates
   `WHERE from_wallet_id = ? OR to_wallet_id = ? … LIMIT ? OFFSET ?` plus a `COUNT(*)` query for the totals.
3. Each entity is mapped to a DTO **from the caller's point of view**:

```java
UUID counterParty = t.getFromWalletId();  Direction dir = INCOMING;
if (t.getFromWalletId().equals(myWalletId)) { counterParty = t.getToWalletId(); dir = OUTGOING; }
```

| If I am… | `direction` | `counterPartyWalletId` |
|---|---|---|
| the sender | `OUTGOING` | receiver's wallet |
| the receiver | `INCOMING` | sender's wallet |

## 13.3 Response shape

```json
{
  "content": [
    {
      "transactionId": "5a8e…",
      "direction": "OUTGOING",
      "counterPartyWalletId": "9d2c…",
      "amount": 100.0000,
      "status": "COMPLETED",
      "createdAt": "2026-09-21T10:20:00Z"
    }
  ],
  "...": "paging metadata: page number, size, totalElements, totalPages"
}
```

The exact layout of the metadata depends on Spring Data's page-serialization mode (the controller returns the `Page` object
directly). If the API needs a stable contract, return a dedicated DTO or Spring Data's `PagedModel` instead of `Page`.

## 13.4 Notes

- **Top-ups don't appear** in history. They create ledger entries but no `transactions` row.
- **Performance:** there's no index on `transactions.from_wallet_id` or `to_wallet_id`, so each page query and its `COUNT(*)` scan the
  whole table. Add two indexes (or query two indexed branches with `UNION ALL`) before the table grows (chapter 17).
- **Offset pagination** gets slower for deep pages and can skip or duplicate rows if new transactions arrive between page requests.
  Keyset ("cursor") pagination on `(created_at, id)` is the usual upgrade for feeds.
