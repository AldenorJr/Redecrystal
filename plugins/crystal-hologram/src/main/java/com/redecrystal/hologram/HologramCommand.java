package com.redecrystal.hologram;

import com.redecrystal.core.CrystalCore;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /hologram set|move|remove|list} — admin command to manage the network
 * holograms. Writes go to the central {@code holograms} config off the main thread;
 * the resulting {@code config-updated} event hot-reloads every server.
 */
final class HologramCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.hologram.admin";
    /** Regex matching the literal two-char sequence {@code \n} typed by the admin. */
    private static final String LINE_SPLIT = "\\\\n";

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final HologramStore store;

    HologramCommand(JavaPlugin plugin, CrystalCore crystal, HologramStore store) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> handleSet(sender, args);
            case "move" -> handleMove(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/hologram set <id> <texto>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        String joined = String.join(" ", List.of(args).subList(2, args.length));
        List<String> lines = List.of(joined.split(LINE_SPLIT, -1));
        Location loc = player.getLocation();
        HologramDef def = new HologramDef(id, loc.getWorld().getName(),
                round(loc.getX()), round(loc.getY()), round(loc.getZ()), lines);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            store.put(def);
            player.sendMessage(Component.text("Holograma '" + id + "' definido para a rede.", NamedTextColor.GREEN));
        });
    }

    private void handleMove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/hologram move <id>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        Location loc = player.getLocation();
        String worldName = loc.getWorld().getName();
        double x = round(loc.getX());
        double y = round(loc.getY());
        double z = round(loc.getZ());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            HologramDef existing = find(id);
            if (existing == null) {
                player.sendMessage(Component.text("Holograma '" + id + "' não existe.", NamedTextColor.RED));
                return;
            }
            HologramDef moved = new HologramDef(existing.id(), worldName, x, y, z, existing.lines());
            store.put(moved);
            player.sendMessage(Component.text("Holograma '" + id + "' movido.", NamedTextColor.GREEN));
        });
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("/hologram remove <id>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean removed = store.remove(id);
            sender.sendMessage(removed
                    ? Component.text("Holograma '" + id + "' removido.", NamedTextColor.GREEN)
                    : Component.text("Holograma '" + id + "' não existe.", NamedTextColor.RED));
        });
    }

    private void handleList(CommandSender sender) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<HologramDef> all = store.all();
            if (all.isEmpty()) {
                sender.sendMessage(Component.text("Nenhum holograma na rede.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text("Hologramas (" + all.size() + "):", NamedTextColor.AQUA));
            for (HologramDef d : all) {
                sender.sendMessage(Component.text(" • " + d.id() + " (" + d.world() + ")", NamedTextColor.GRAY));
            }
        });
    }

    private HologramDef find(String id) {
        for (HologramDef d : store.all()) {
            if (d.id().equalsIgnoreCase(id)) {
                return d;
            }
        }
        return null;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/hologram set <id> <texto> | move <id> | remove <id> | list", NamedTextColor.GRAY));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
