-- Service Discovery registry. Instances auto-register on startup (server-started)
-- and heartbeat; the proxy reads this to balance players. No code change is
-- required to add lobby-04, login-03, etc. — only a new container/SERVER_ID.
CREATE TABLE server_registry (
    server_id       VARCHAR(64)  PRIMARY KEY,      -- e.g. 'lobby-01', 'proxy-02'
    type            VARCHAR(32)  NOT NULL,         -- 'proxy' | 'login' | 'lobby' | 'events'
    host            VARCHAR(255) NOT NULL,
    port            INTEGER      NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'STARTING', -- STARTING|ONLINE|DRAINING|OFFLINE
    max_players     INTEGER      NOT NULL DEFAULT 0,
    online_players  INTEGER      NOT NULL DEFAULT 0,
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_heartbeat  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_server_registry_type_status ON server_registry (type, status);
