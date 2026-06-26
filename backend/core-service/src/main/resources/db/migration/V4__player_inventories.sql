-- Synced inventories, one row per (player, server type). `content` holds the
-- Base64 of Paper's serialized item array; `version` drives optimistic locking
-- so a stale save (e.g. during a fast server switch) is rejected, not silently
-- overwritten. Redis (inventory:{uuid}:{type}) is the write-through hot cache.
CREATE TABLE player_inventories (
    player_uuid   UUID         NOT NULL,
    server_type   VARCHAR(32)  NOT NULL,
    content       TEXT         NOT NULL DEFAULT '',
    version       INTEGER      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, server_type)
);
