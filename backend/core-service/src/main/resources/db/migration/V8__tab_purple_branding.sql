-- Premium tab-list branding: multi-line header/footer with a fully purple
-- "RedeCrystal" wordmark (matches the proxy MOTD in velocity.toml). Placeholders
-- {player} {server} {online} {max} {ping} are filled by crystal-tab.
-- Merged into the existing 'tab' row.
UPDATE network_configs
SET config = config || '{
      "header": "\n<gradient:#b14aed:#8e2de2><bold>R E D E   C R Y S T A L</bold></gradient>\n<#c9a6ff>✧ <gray>A sua aventura começa aqui <#c9a6ff>✧\n<gray>Olá, <white>{player}</white>!\n",
      "footer": "\n<dark_gray><strikethrough>                              </strikethrough></dark_gray>\n<gray>Você está em <#b14aed>{server}   <dark_gray>|</dark_gray>   <gray>Online: <#b14aed>{online}<gray>/<#b14aed>{max}\n<gray>Ping: <#b14aed>{ping}ms   <dark_gray>|</dark_gray>   <#c9a6ff>play.redecrystal.net\n",
      "maxPlayers": 500
   }'::jsonb,
    version = version + 1,
    updated_at = now()
WHERE config_key = 'tab';
