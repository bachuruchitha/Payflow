CREATE TABLE users (
   id   UUID   PRIMARY KEY,
   email  VARCHAR(255) UNIQUE NOT NULL,
   password_hash VARCHAR(255) NOT NULL,
   created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);