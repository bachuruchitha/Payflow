ALTER TABLE wallets ADD COLUMN balance NUMERIC(19,4) NOT NULL DEFAULT 0;

CREATE TABLE ledger_entries (
   id UUID   PRIMARY KEY,
   transaction_id UUID,
   wallet_id UUID NOT NULL REFERENCES wallets(id),
   entry_type VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
   amount NUMERIC(19,4) NOT NULL CHECK (amount>0),
   created_at TIMESTAMPTZ NOT NULL DEFAULT now() );