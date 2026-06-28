-- Chat tags/cargos. The 'chat' config key carries the role definitions used by
-- BOTH crystal-chat (prefix + colored name in the network-wide chat) and
-- crystal-tab (the same tag in the player-list). Each role is matched on the
-- player's server by the permission node (LuckPerms); when a player has several,
-- the highest 'weight' wins. Prefix/name-color use MiniMessage (legacy '&' codes
-- are also accepted by the plugins). Tags are wrapped in [BRACKETS].
--
-- chatFormat placeholders: <prefix> <player_name> <player> <server> <message>
--
-- Upsert-and-merge so an existing 'chat' row (e.g. with bannedWords) is preserved.
INSERT INTO network_configs (config_key, config) VALUES
  ('chat', '{
     "chatFormat": "<prefix> <player_name><gray>:</gray> <message>",
     "bannedWords": [],
     "roles": {
       "ceo":           { "permission": "tag.ceo",           "weight": 130, "prefix": "<bold><gradient:#ff0000:#ffcc00>[CEO]</gradient></bold>",      "nameColor": "<#ff5555>" },
       "diretor":       { "permission": "tag.diretor",       "weight": 120, "prefix": "<bold><#ff7b00>[DIRETOR]</#ff7b00></bold>",                   "nameColor": "<#ffaa55>" },
       "gerente":       { "permission": "tag.gerente",       "weight": 110, "prefix": "<bold><#ffcc00>[GERENTE]</#ffcc00></bold>",                   "nameColor": "<#ffdd55>" },
       "administrador": { "permission": "tag.administrador", "weight": 100, "prefix": "<bold><dark_red>[ADMIN]</dark_red></bold>",                   "nameColor": "<red>" },
       "moderador":     { "permission": "tag.moderador",     "weight": 90,  "prefix": "<bold><gold>[MOD]</gold></bold>",                             "nameColor": "<yellow>" },
       "ajudante":      { "permission": "tag.ajudante",      "weight": 80,  "prefix": "<bold><green>[AJUDANTE]</green></bold>",                      "nameColor": "<green>" },
       "youtuber":      { "permission": "tag.youtuber",      "weight": 70,  "prefix": "<bold><light_purple>[YOUTUBER]</light_purple></bold>",       "nameColor": "<light_purple>" },
       "midia":         { "permission": "tag.midia",         "weight": 60,  "prefix": "<bold><#d633ff>[MIDIA]</#d633ff></bold>",                     "nameColor": "<#e699ff>" },
       "apoiador":      { "permission": "tag.apoiador",      "weight": 50,  "prefix": "<bold><red>[APOIADOR]</red></bold>",                          "nameColor": "<red>" },
       "crystal":       { "permission": "tag.crystal",       "weight": 40,  "prefix": "<bold><gradient:#00ffff:#66ccff>[CRYSTAL]</gradient></bold>", "nameColor": "<aqua>" },
       "diamante":      { "permission": "tag.diamante",      "weight": 30,  "prefix": "<bold><aqua>[DIAMANTE]</aqua></bold>",                        "nameColor": "<aqua>" },
       "ouro":          { "permission": "tag.ouro",          "weight": 20,  "prefix": "<bold><yellow>[OURO]</yellow></bold>",                        "nameColor": "<yellow>" },
       "membro":        { "permission": "tag.membro",        "weight": 10,  "prefix": "<gray>[MEMBRO]</gray>",                                       "nameColor": "<gray>" }
     },
     "defaultRole": "membro"
   }'::jsonb)
ON CONFLICT (config_key) DO UPDATE
  SET config = network_configs.config
               || jsonb_build_object('roles',       EXCLUDED.config->'roles')
               || jsonb_build_object('chatFormat',  EXCLUDED.config->'chatFormat')
               || jsonb_build_object('defaultRole', EXCLUDED.config->'defaultRole'),
      version = network_configs.version + 1,
      updated_at = now();
