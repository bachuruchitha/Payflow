# 15 — API Reference

Base URL: `http://localhost:8080`. Interactive docs: `/swagger-ui.html`. OpenAPI JSON: `/v3/api-docs`.

Auth column: 🔓 public, 🔒 requires `Authorization: Bearer <jwt>`.

| Method | Path | Auth | Purpose | Controller |
|---|---|---|---|---|
| GET | `/health` | 🔓 | Liveness | `HealthController` |
| POST | `/api/users/register` | 🔓 | Create user + wallet | `UserController` |
| POST | `/api/auth/login` | 🔓 | Get a JWT | `AuthController` |
| GET | `/api/wallets/me` | 🔒 | My wallet + balance | `WalletController` |
| POST | `/api/wallets/me/topup` | 🔒 | Add money | `WalletController` |
| POST | `/api/transfers` | 🔒 | Send money (rate-limited, idempotent) | `TransferController` |
| GET | `/api/transactions` | 🔒 | My history (paginated) | `TransferController` |

All error responses use `{"code","message","timestamp"}` (chapter 14).

---

## GET `/health`

Response `200 text/plain`: `OK`

It doesn't check the DB, Redis or Kafka. It only shows the JVM is serving HTTP.

---

## POST `/api/users/register`

```json
// request
{ "email": "alice@example.com", "password": "secret" }
// 200 response
{ "id": "7c1e0b7a-…", "email": "alice@example.com" }
```

| Validation | Rule |
|---|---|
| `email` | `@NotBlank @Email` |
| `password` | `@NotBlank` |

Errors: `400 INVALID_DATA`, `409 EMAIL_ALREADY_EXISTS`, `409 DATA_VIOLATION` (simultaneous duplicate).

Side effect: creates a USD wallet with balance 0.

---

## POST `/api/auth/login`

```json
// request
{ "email": "alice@example.com", "password": "secret" }
// 200 response
{ "jwtToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOi…" }
```

Errors: `400 INVALID_DATA`, `401 INVALID_CREDENTIALS`. The token is valid for 1 hour.

---

## GET `/api/wallets/me` 🔒

```json
// 200 response
{
  "id": "3f1b…",            // wallet id: give this to people who want to pay you
  "userId": "7c1e…",
  "currency": "USD",
  "createdAt": "2026-09-21T10:15:30Z",
  "balance": 400.0000
}
```

Errors: `404 WALLET_NOT_FOUND`, `403` (no/invalid token).

---

## POST `/api/wallets/me/topup` 🔒

```json
// request
{ "amount": 500.00 }
// 200 response: same shape as GET /api/wallets/me, with the new balance
```

| Validation | Rule |
|---|---|
| `amount` | `@NotNull @Positive` |

Errors: `400 INVALID_DATA`, `404 WALLET_NOT_FOUND`, `500` if it collides with a concurrent write on the same wallet (chapter 05).
Not idempotent: don't blindly retry.

---

## POST `/api/transfers` 🔒

Headers:

| Header | Required | Notes |
|---|---|---|
| `Authorization` | yes | `Bearer <jwt>`; the sender is taken from here |
| `Idempotency-Key` | **yes** | Unique per logical payment; reuse on retry. Missing → currently `500` |
| `Content-Type` | yes | `application/json` |

```json
// request
{ "toWalletId": "9d2c…", "amount": 100.00 }
// 200 response
{ "transactionId": "5a8e…", "status": "COMPLETED", "amount": 100.00 }
```

| Validation | Rule |
|---|---|
| `toWalletId` | `@NotNull` UUID, must be a **wallet** id |
| `amount` | `@NotNull @Positive` (use ≤ 2 decimals; see chapter 17, issue #1) |

| Status | Code | When |
|---|---|---|
| 200 | — | Executed, or replayed for a key that was already used |
| 400 | `INVALID_DATA` | Body validation failed |
| 400 | `SELF_TRANSFER_NOT_ALLOWED` | `toWalletId` is your own wallet |
| 404 | `WALLET_NOT_FOUND` | Your wallet or the receiver's doesn't exist |
| 409 | `INSUFFICIENT_BALANCE` | Balance < amount |
| 409 | `TRANSFER_CONFLICT` | Lost 3 optimistic races; retry with the **same** key |
| 429 | `RATE_LIMIT_EXCEEDED` | Over 10 burst / 10 per minute; see the `Retry-After` header |

Side effects: 1 `transactions` row, 2 `ledger_entries`, 2 balance updates, 1 `outbox_events` row → Kafka → 1 `notifications` row.

---

## GET `/api/transactions` 🔒

Query: `page` (default 0), `size` (default 20), `sort` (e.g. `createdAt,desc`; **pass it**, because there's no default order).

```json
// 200 response (Spring Data Page)
{
  "content": [
    { "transactionId": "5a8e…", "direction": "OUTGOING", "counterPartyWalletId": "9d2c…",
      "amount": 100.0000, "status": "COMPLETED", "createdAt": "2026-09-21T10:20:00Z" }
  ]
  // + paging metadata (page, size, totalElements, totalPages)
}
```

Errors: `404 WALLET_NOT_FOUND`, `500` for an unknown sort property.
