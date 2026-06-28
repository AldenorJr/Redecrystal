package com.redecrystal.lobby.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.lobby.CrystalLobbyPlugin;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * {@code /manutencao <on|off>} — toggles {@code global.maintenance} for the whole
 * network. The write goes to the central config so every proxy/lobby reacts.
 */
public final class MaintenanceCommand implements CommandExecutor {

    private static final String MAINT_PERM = "crystal.maintenance";
    private static final String GLOBAL_KEY = "global";

    private final CrystalLobbyPlugin plugin;
    private final CrystalCore crystal;

    public MaintenanceCommand(CrystalLobbyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(MAINT_PERM)) {
            sender.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            boolean current = crystal.configProvider().get(GLOBAL_KEY).bool("maintenance", false);
            sender.sendMessage(Component.text("Manutenção está " + (current ? "LIGADA" : "DESLIGADA")
                    + ". Use /manutencao <on|off>.", NamedTextColor.YELLOW));
            return true;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        final boolean enable;
        if (arg.equals("on") || arg.equals("ligar") || arg.equals("true")) {
            enable = true;
        } else if (arg.equals("off") || arg.equals("desligar") || arg.equals("false")) {
            enable = false;
        } else {
            sender.sendMessage(Component.text("Use /manutencao <on|off>.", NamedTextColor.GRAY));
            return true;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(GLOBAL_KEY).config());
                cfg.put("maintenance", enable);
                crystal.backend().putConfig(GLOBAL_KEY, cfg);
                sender.sendMessage(Component.text("Manutenção " + (enable ? "LIGADA" : "DESLIGADA")
                        + " para toda a rede.", enable ? NamedTextColor.GOLD : NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Falha ao alterar manutenção: " + e.getMessage(),
                        NamedTextColor.RED));
            }
        });
        return true;
    }
}
