-- Player progression profile, keyed by Mojang/offline UUID. Created on demand
-- (no FK to a players table yet — auth-service owns identity later).
CREATE TABLE player_profiles (
    player_uuid   UUID         PRIMARY KEY,
    username      VARCHAR(16),
    rank          VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
    level         INTEGER      NOT NULL DEFAULT 1,
    experience    BIGINT       NOT NULL DEFAULT 0,
    coins         BIGINT       NOT NULL DEFAULT 0,
    play_seconds  BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
