CREATE TABLE processed_events (
transaction_id UUID PRIMARY KEY,
processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
id UUID PRIMARY KEY,
transaction_id UUID NOT NULL,
message TEXT NOT NULL,
created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
