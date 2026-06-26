-- Maintenance branding: a maintenance MOTD/kick (rendered by crystal-bungee from
-- code, purple RedeCrystal) plus a staff bypass list, and a maintenance tab
-- header/footer (crystal-tab) shown to staff who get in during maintenance.
--   global.maintenanceBypass : player names allowed to log in during maintenance
--   tab.maintenanceHeader/Footer : MiniMessage shown in the tab while in maintenance
UPDATE network_configs
SET config = config || '{"maintenanceBypass": []}'::jsonb,
    version = version + 1,
    updated_at = now()
WHERE config_key = 'global';

UPDATE network_configs
SET config = config || '{
      "maintenanceHeader": "\n<red><bold>⚠ EM MANUTENÇÃO ⚠</bold></red>\n<#c9a6ff>Estamos melhorando a rede para você\n",
      "maintenanceFooter": "\n<gray>Acesso liberado para a equipe   <dark_gray>|</dark_gray>   <#c9a6ff>play.redecrystal.net\n"
   }'::jsonb,
    version = version + 1,
    updated_at = now()
WHERE config_key = 'tab';
