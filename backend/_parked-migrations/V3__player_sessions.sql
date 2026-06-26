-- Connection sessions. Hot state lives in Redis (session:{uuid}); this table is
-- the durable audit/history record. `active = TRUE` marks a live session.
CREATE TABLE player_sessions (
    id               UUID         PRIMARY KEY,
    player_id        UUID         NOT NULL REFERENCES players (id) ON DELETE CASCADE,
    proxy_id         VARCHAR(64),
    current_server   VARCHAR(64),
    ip_address       INET,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    connected_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disconnected_at  TIMESTAMPTZ
);

CREATE INDEX ix_player_sessions_player ON player_sessions (player_id);
CREATE INDEX ix_player_sessions_active ON player_sessions (active) WHERE active;
