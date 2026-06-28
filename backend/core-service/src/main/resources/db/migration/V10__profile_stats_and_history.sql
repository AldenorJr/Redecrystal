-- Profile stats expansion + full player history.
--
-- 1) New network-wide counters on the profile (kills/deaths/messages). Coins,
--    level and rank stay in the table (not dropped, to preserve data) but the
--    profile UI no longer shows them — it shows the cargo (from LuckPerms) +
--    these stats instead.
ALTER TABLE player_profiles
    ADD COLUMN IF NOT EXISTS kills         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deaths        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS messages_sent BIGINT NOT NULL DEFAULT 0;

-- 2) Chat history — every message sent on the network (global and private /tell).
CREATE TABLE chat_messages (
    id           BIGSERIAL    PRIMARY KEY,
    player_uuid  UUID         NOT NULL,
    username     VARCHAR(16),
    server       VARCHAR(64),
    scope        VARCHAR(16)  NOT NULL DEFAULT 'global',  -- 'global' | 'tell'
    target       VARCHAR(16),                              -- recipient for /tell
    message      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_player ON chat_messages (player_uuid, created_at DESC);
CREATE INDEX idx_chat_messages_time   ON chat_messages (created_at DESC);

-- 3) Activity history — a trail of everything a player did (join/quit, chat,
--    kills, deaths, commands, server switches, ...).
CREATE TABLE player_activity (
    id           BIGSERIAL    PRIMARY KEY,
    player_uuid  UUID         NOT NULL,
    username     VARCHAR(16),
    type         VARCHAR(32)  NOT NULL,    -- JOIN | QUIT | CHAT | TELL | KILL | DEATH | COMMAND | ...
    detail       TEXT,
    server       VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_player_activity_player ON player_activity (player_uuid, created_at DESC);
CREATE INDEX idx_player_activity_type   ON player_activity (type, created_at DESC);
