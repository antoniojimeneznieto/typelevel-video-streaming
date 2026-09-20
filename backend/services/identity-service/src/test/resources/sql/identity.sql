CREATE TABLE users (
  id            UUID        PRIMARY KEY,
  email         TEXT        NOT NULL,
  password_hash TEXT        NOT NULL,
  display_name  TEXT        NOT NULL,
  role          TEXT        NOT NULL CHECK (role IN ('student', 'admin')),
  status        TEXT        NOT NULL CHECK (status IN ('active', 'disabled')),
  created_at    TIMESTAMPTZ NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX users_email_unique ON users (lower(email));

CREATE TABLE IF NOT EXISTS outbox (
  id            UUID  PRIMARY KEY,
  aggregatetype TEXT  NOT NULL,
  aggregateid   TEXT  NOT NULL,
  type          TEXT  NOT NULL,
  payload       JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object')
);
