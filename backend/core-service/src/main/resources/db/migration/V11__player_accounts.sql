-- Player accounts — network login identity. One row per account.
-- `password_hash` is NULL for premium (Mojang-authenticated) accounts; cracked
-- accounts store a bcrypt hash. Hot session state lives in Redis (session:{uuid},
-- jwt:{uuid}); this table is the durable source of truth for credentials.
CREATE TABLE player_accounts (
    id              UUID         PRIMARY KEY,
    username        VARCHAR(16)  NOT NULL,
    username_lower  VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(255),                 -- NULL for premium accounts
    premium         BOOLEAN      NOT NULL DEFAULT FALSE,
    first_login_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_player_accounts_username_lower ON player_accounts (username_lower);
