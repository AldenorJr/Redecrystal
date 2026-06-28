-- Chat tags/cargos. The 'chat' config key carries the role definitions used by
-- BOTH crystal-chat (prefix + colored name in the network-wide chat) and
-- crystal-tab (the same tag in the player-list). Each role is matched on the
-- player's server by the permission node (LuckPerms); when a player has several,
-- the highest 'weight' wins. Prefix/name-color use MiniMessage (legacy '&' codes
-- are also accepted by the plugins). Tags are wrapped in [BRACKETS]. Convention:
-- prefixes are NOT bold and the player name renders white (nameColor "<white>").
--
-- chatFormat placeholders: <prefix> <player_name> <player> <server> <message>
--
-- Upsert-and-merge so an existing 'chat' row (e.g. with bannedWords) is preserved.
INSERT INTO network_configs (config_key, config) VALUES
  ('chat', '{
     "chatFormat": "<prefix> <player_name><gray>:</gray> <message>",
     "bannedWords": [],
     "roles": {
       "ceo":           { "permission": "tag.ceo",           "weight": 130, "prefix": "<gradient:#ff5555:#990000>[CEO]</gradient>",          "nameColor": "<white>" },
       "diretor":       { "permission": "tag.diretor",       "weight": 120, "prefix": "<#ff7b00>[DIRETOR]</#ff7b00>",                       "nameColor": "<white>" },
       "gerente":       { "permission": "tag.gerente",       "weight": 110, "prefix": "<gradient:#ff5555:#990000>[GERENTE]</gradient>",      "nameColor": "<white>" },
       "administrador": { "permission": "tag.administrador", "weight": 100, "prefix": "<dark_red>[ADMIN]</dark_red>",                       "nameColor": "<white>" },
       "moderador":     { "permission": "tag.moderador",     "weight": 90,  "prefix": "<gradient:#55ff55:#118811>[MODERADOR]</gradient>",    "nameColor": "<white>" },
       "ajudante":      { "permission": "tag.ajudante",      "weight": 80,  "prefix": "<green>[AJUDANTE]</green>",                           "nameColor": "<white>" },
       "youtuber":      { "permission": "tag.youtuber",      "weight": 70,  "prefix": "<light_purple>[YOUTUBER]</light_purple>",            "nameColor": "<white>" },
       "midia":         { "permission": "tag.midia",         "weight": 60,  "prefix": "<#d633ff>[MIDIA]</#d633ff>",                          "nameColor": "<white>" },
       "apoiador":      { "permission": "tag.apoiador",      "weight": 50,  "prefix": "<red>[APOIADOR]</red>",                               "nameColor": "<white>" },
       "crystal":       { "permission": "tag.crystal",       "weight": 40,  "prefix": "<gradient:#00ffff:#66ccff>[CRYSTAL]</gradient>",      "nameColor": "<white>" },
       "diamante":      { "permission": "tag.diamante",      "weight": 30,  "prefix": "<aqua>[DIAMANTE]</aqua>",                             "nameColor": "<white>" },
       "ouro":          { "permission": "tag.ouro",          "weight": 20,  "prefix": "<yellow>[OURO]</yellow>",                             "nameColor": "<white>" },
       "membro":        { "permission": "tag.membro",        "weight": 10,  "prefix": "<gray>[MEMBRO]</gray>",                               "nameColor": "<white>" }
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
