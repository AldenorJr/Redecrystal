package com.redecrystal.parkour.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.parkour.CrystalParkourPlugin;
import com.redecrystal.parkour.ParkourCourse;
import com.redecrystal.parkour.listener.ParkourListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /parkour} (alias {@code /pk}): the player atalho and the admin course
 * editor. Play/exit are delegated to {@link ParkourListener} (which owns the run
 * state); the {@code set*} subcommands mutate the central parkour config and let
 * it hot-reload, mirroring how the lobby spawn is configured. The run-time
 * behaviour lives in the listener — this class only routes the command.
 */
public final class ParkourCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.parkour.admin";

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;
    private final ParkourListener listener;

    public ParkourCommand(CrystalParkourPlugin plugin, CrystalCore crystal, ParkourListener listener) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        String sub = args.length == 0 ? "play" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> listener.startPlaying(player);
            case "top" -> showTop(player);
            case "reset", "sair" -> listener.exitRun(player);
            case "setspawn" -> admin(player,
                    m -> m.put("spawn", ParkourCourse.facingMap(player.getLocation())),
                    "spawn (chegada da bússola) definido");
            case "setstart" -> admin(player,
                    m -> m.put("start", ParkourCourse.pointMap(player.getLocation())),
                    "início definido");
            case "setcheckpoint" -> admin(player, m -> {
                @SuppressWarnings("unchecked")
                List<Object> cps = new ArrayList<>((List<Object>) m.getOrDefault("checkpoints", new ArrayList<>()));
                cps.add(ParkourCourse.pointMap(player.getLocation()));
                m.put("checkpoints", cps);
            }, "checkpoint adicionado");
            case "removecheckpoint", "undocheckpoint" -> admin(player, m -> {
                if (m.get("checkpoints") instanceof List<?> l && !l.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Object> cps = new ArrayList<>((List<Object>) l);
                    cps.remove(cps.size() - 1);
                    m.put("checkpoints", cps);
                }
            }, "último checkpoint removido");
            case "setfinish" -> admin(player,
                    m -> m.put("finish", ParkourCourse.pointMap(player.getLocation())),
                    "fim definido");
            case "setfall" -> admin(player,
                    m -> m.put("fallY", Math.round(player.getLocation().getY() - 3)),
                    "plano de queda definido");
            case "clear" -> admin(player, m -> {
                m.remove("spawn");
                m.remove("start");
                m.remove("checkpoints");
                m.remove("finish");
            }, "curso limpo");
            case "reload" -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.refreshCourse(crystal.backend().getConfig(CrystalParkourPlugin.CONFIG_KEY).config());
                plugin.getServer().getScheduler().runTask(plugin, plugin::rebuildHolograms);
                player.sendMessage(Component.text("Curso recarregado.", NamedTextColor.GREEN));
            });
            default -> player.sendMessage(Component.text(
                    "/parkour [top|reset|setspawn|setstart|setcheckpoint|removecheckpoint|setfinish|setfall|clear|reload]",
                    NamedTextColor.GRAY));
        }
        return true;
    }

    private void showTop(Player player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ParkourEntry> top = crystal.backend().parkourTop(10);
            player.sendMessage(Component.text("— Ranking Parkour (menor tempo) —", NamedTextColor.GOLD));
            if (top.isEmpty()) {
                player.sendMessage(Component.text("Ninguém completou ainda.", NamedTextColor.GRAY));
            }
            for (ParkourEntry e : top) {
                player.sendMessage(Component.text("#" + e.rank() + " ", NamedTextColor.YELLOW)
                        .append(Component.text(e.username() + " ", NamedTextColor.WHITE))
                        .append(Component.text(CrystalParkourPlugin.formatTime(e.timeMs()), NamedTextColor.AQUA)));
            }
        });
    }

    /** Admin helper: mutate the central parkour config and persist it (hot-reloads). */
    private void admin(Player player, Consumer<Map<String, Object>> mutator, String okMsg) {
        if (!player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CrystalParkourPlugin.CONFIG_KEY).config());
            cfg.put("world", player.getWorld().getName());
            cfg.putIfAbsent("fallY", Math.round(player.getLocation().getY() - 5));
            mutator.accept(cfg);
            crystal.backend().putConfig(CrystalParkourPlugin.CONFIG_KEY, cfg);
            player.sendMessage(Component.text("Parkour: " + okMsg + ".", NamedTextColor.GREEN));
        });
    }
}
