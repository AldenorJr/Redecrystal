-- Synced inventories. Redis is the hot write-through cache (inventory:{uuid});
-- this is the durable store. `version` enables optimistic concurrency to avoid
-- clobbering on fast proxy-initiated server switches.
CREATE TABLE player_inventories (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    player_id     UUID         NOT NULL REFERENCES players (id) ON DELETE CASCADE,
    server_type   VARCHAR(32)  NOT NULL,          -- e.g. 'lobby', 'login', 'events'
    content       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version       INTEGER      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_player_inventories PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_player_inventories
    ON player_inventories (player_id, server_type);
