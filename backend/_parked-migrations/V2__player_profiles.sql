-- Gameplay/progression profile. 1:1 with players.
CREATE TABLE player_profiles (
    player_id     UUID         PRIMARY KEY REFERENCES players (id) ON DELETE CASCADE,
    display_name  VARCHAR(64),
    rank          VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
    level         INTEGER      NOT NULL DEFAULT 1,
    experience    BIGINT       NOT NULL DEFAULT 0,
    coins         BIGINT       NOT NULL DEFAULT 0,
    gems          BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_player_profiles_rank ON player_profiles (rank);
