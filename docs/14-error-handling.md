# 14 — Error Handling

**Files:** `exception/*.java`, `exception/GlobalExceptionHandler.java`, `dto/ErrorResponse.java`

## 14.1 The approach

- Business rules throw **specific unchecked exceptions** (`extends RuntimeException`). A `RuntimeException` thrown from a
  `@Transactional` method triggers a **rollback** by default, which is exactly what's wanted when a transfer is rejected halfway through.
- One `@ControllerAdvice` class, `GlobalExceptionHandler`, maps each exception to an HTTP status and a stable, machine-readable
  **error code**.
- Every error body has the same shape (`ErrorResponse` record):

```json
{ "code": "INSUFFICIENT_BALANCE", "message": "Insufficient balance", "timestamp": "2026-09-21T10:20:00.123Z" }
```

Clients should branch on `code`, not on `message`.

## 14.2 Complete mapping

| Exception | Thrown by | HTTP | `code` | `message` |
|---|---|---|---|---|
| `MethodArgumentNotValidException` | Spring, when `@Valid` fails | 400 | `INVALID_DATA` | `Validation failed` (field details are not included) |
| `SelfTransferNotAllowedException` | both executors | 400 | `SELF_TRANSFER_NOT_ALLOWED` | `Self transfer not allowed` |
| `InvalidCredentialsException` | `AuthService.login` | 401 | `INVALID_CREDENTIALS` | `Incorrect email or password` |
| `WalletNotFoundException` | wallet lookups | 404 | `WALLET_NOT_FOUND` | `No wallet found for the user` (also used when the **receiver** wallet doesn't exist) |
| `DuplicateEmailException` | `UserService.register` | 409 | `EMAIL_ALREADY_EXISTS` | `Email already exists` |
| `InsufficientBalanceException` | both executors | 409 | `INSUFFICIENT_BALANCE` | `Insufficient balance` |
| `TransferConflictException` | `OptimisticTransferService` after 3 lost attempts | 409 | `TRANSFER_CONFLICT` | `Wallet is busy with other transfers, please retry (gave up after 3 attempts)` |
| `DataIntegrityViolationException` | DB constraint (e.g. racing duplicate email) | 409 | `DATA_VIOLATION` | `Data violation` |
| `TooManyRequestsException` | `TransferController` | 429 + `Retry-After` header | `RATE_LIMIT_EXCEEDED` | `Too many requests. Retry after Ns` |
| **any other `Exception`** | — | 500 | `INTERNAL_ERROR` | `An unexpected error occurred` |

Security errors never reach this handler. They're raised in the servlet filter chain, *before* Spring MVC. An unauthenticated
request to a protected endpoint gets Spring Security's default **403 with an empty body**.

## 14.3 Design notes on specific choices

- **409 for insufficient balance.** The request is well-formed but conflicts with the current state of the resource. (Some APIs use 422 or
  402; 409 is defensible.)
- **`TransferConflictException` carries the last conflict as its `cause`**, so the stack trace survives for debugging, and its comment
  documents that nothing was committed and the client can safely retry.
- **`TooManyRequestsException` carries `retryAfterSeconds`**, so the handler just writes the header without knowing anything about
  buckets.
- **The catch-all hides internals** from the client (no stack traces or SQL in responses), which is good security hygiene.

## 14.4 Weaknesses (fixes in chapter 17)

1. **The catch-all doesn't log.** A 500 leaves no trace in the logs, so production bugs are invisible. Add `log.error("Unhandled", ex)`.
2. **Client mistakes become 500s.** These Spring exceptions aren't mapped, so they fall into the catch-all:
   - missing `Idempotency-Key` header → `MissingRequestHeaderException`
   - malformed JSON / wrong types → `HttpMessageNotReadableException`
   - wrong HTTP method → `HttpRequestMethodNotSupportedException` (normally 405)
   - unknown sort property → `PropertyReferenceException`
   - an unhandled `ObjectOptimisticLockingFailureException` from a racing **top-up**
   
   Extending `ResponseEntityExceptionHandler` maps most of Spring's own exceptions to proper 4xx codes automatically.
3. **Validation errors don't say which field failed.** Include `ex.getBindingResult().getFieldErrors()` in the response.
4. **`DATA_VIOLATION` is generic.** A racing duplicate email should really return `EMAIL_ALREADY_EXISTS`.
