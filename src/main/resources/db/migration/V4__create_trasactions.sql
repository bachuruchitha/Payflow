CREATE TABLE transactions (
   id UUID   PRIMARY KEY,
   from_wallet_id UUID NOT NULL REFERENCES wallets(id),
   to_wallet_id UUID NOT NULL REFERENCES wallets(id),
   status VARCHAR(6) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
   amount NUMERIC(19,4) NOT NULL CHECK (amount>0),
   created_at TIMESTAMPTZ NOT NULL DEFAULT now() ,
completed_at TIMESTAMPTZ );