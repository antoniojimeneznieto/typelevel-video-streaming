CREATE TABLE user_profile (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  keycloak_id  TEXT UNIQUE NOT NULL,
  username     TEXT,
  email        TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
