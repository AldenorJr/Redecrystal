package com.redecrystal.lobby.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.lobby.CrystalLobbyPlugin;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /lobby} — teleport to the lobby spawn. {@code /lobby setspawn} (admins)
 * writes the current location to the central lobby config, hot-reloading the
 * spawn on every lobby instance.
 */
public final class LobbyCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.lobby.admin";
    private static final String CONFIG_KEY = "lobby";

    private final CrystalLobbyPlugin plugin;
    private final CrystalCore crystal;

    public LobbyCommand(CrystalLobbyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (args.length == 0) {
            plugin.sendToSpawn(player);
            return true;
        }
        if ("setspawn".equalsIgnoreCase(args[0])) {
            if (!player.hasPermission(ADMIN_PERM)) {
                player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            Location loc = player.getLocation();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
                cfg.put("spawn", Map.of(
                        "x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                        "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch())));
                crystal.backend().putConfig(CONFIG_KEY, cfg);
                player.sendMessage(Component.text("Spawn do lobby definido para todos os lobbies.", NamedTextColor.GREEN));
            });
            return true;
        }
        player.sendMessage(Component.text("/lobby [setspawn]", NamedTextColor.GRAY));
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
