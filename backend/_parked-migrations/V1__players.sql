-- Core player identity. One row per account on the network.
CREATE TABLE players (
    id              UUID         PRIMARY KEY,
    username        VARCHAR(16)  NOT NULL,
    username_lower  VARCHAR(16)  NOT NULL,
    email           VARCHAR(255),
    password_hash   VARCHAR(255),                 -- NULL for premium (Mojang-authenticated) accounts
    premium         BOOLEAN      NOT NULL DEFAULT FALSE,
    first_login_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_players_username_lower ON players (username_lower);
CREATE INDEX ix_players_email ON players (email);
