-- Lobby spawn + void threshold (used by /lobby setspawn and lobby protection),
-- and the tab-list config. Merged into the existing 'lobby' row; 'tab' inserted.
UPDATE network_configs
SET config = config || '{"spawn": {"x": 0.5, "y": 66, "z": 0.5, "yaw": 0, "pitch": 0}, "voidY": 30}'::jsonb,
    version = version + 1,
    updated_at = now()
WHERE config_key = 'lobby';

INSERT INTO network_configs (config_key, config) VALUES
  ('tab', '{
     "header": "<gradient:#5dade2:#a569bd><bold>RedeCrystal</bold></gradient>\n<gray>Bem-vindo, <white>{player}</white>!",
     "footer": "<gray>Online: <yellow>{online}</yellow>   <dark_gray>|</dark_gray>   <gray>Ping: <green>{ping}ms</green>\n<aqua>play.redecrystal.net",
     "refreshTicks": 40,
     "prefixInTab": true
   }'::jsonb)
ON CONFLICT (config_key) DO NOTHING;
