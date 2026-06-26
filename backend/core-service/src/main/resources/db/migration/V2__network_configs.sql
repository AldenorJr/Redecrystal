-- Centralized configuration (the Config Service source of truth). Plugins never
-- store config locally — they fetch by key and hot-reload on `config-updated`.
-- Redis mirrors these rows under config:* for low-latency reads.
CREATE TABLE network_configs (
    config_key  VARCHAR(64)  PRIMARY KEY,          -- e.g. 'lobby', 'login', 'proxy', 'global'
    config      JSONB        NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed sensible defaults so a fresh stack boots fully configured.
INSERT INTO network_configs (config_key, config) VALUES
  ('global', '{"networkName": "RedeCrystal", "maintenance": false, "motd": "RedeCrystal"}'::jsonb),
  ('proxy',  '{"server": "proxy", "maxPlayers": 1000, "maintenance": false, "motd": "RedeCrystal"}'::jsonb),
  ('login',  '{"server": "login", "maxPlayers": 200,  "maintenance": false, "motd": "RedeCrystal"}'::jsonb),
  ('lobby',  '{"server": "lobby", "maxPlayers": 500,  "maintenance": false, "motd": "RedeCrystal"}'::jsonb);
