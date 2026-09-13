-- V5__add_idempotency_key_to_transactions.sql

-- 1. Add the column as nullable, so the ADD succeeds on existing rows
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);

-- 2. Backfill existing rows with a unique value (their own id, cast to text)
UPDATE transactions SET idempotency_key = id::text WHERE idempotency_key IS NULL;

-- 3. Now that every row has a value, enforce NOT NULL
ALTER TABLE transactions ALTER COLUMN idempotency_key SET NOT NULL;

-- 4. Enforce uniqueness — the database guarantee against double-spend
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key);