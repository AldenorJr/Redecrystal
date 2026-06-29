package com.redecrystal.parkour.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.parkour.CrystalParkourPlugin;
import com.redecrystal.parkour.ParkourCourse;
import com.redecrystal.parkour.listener.ParkourListener;
import com.redecrystal.parkour.menu.ParkourTopMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /parkour} (alias {@code /pk}): player shortcuts ({@code top}, {@code reset})
 * plus the admin course editor grouped under {@code /parkour admin <...>}. Play/exit
 * are delegated to {@link ParkourListener}; {@code top} opens {@link ParkourTopMenu};
 * the {@code admin} subcommands mutate the central parkour config (hot-reloaded).
 */
public final class ParkourCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERM = "crystal.parkour.admin";
    private static final List<String> ADMIN_SUBS = List.of(
            "setspawn", "setstart", "setcheckpoint", "removecheckpoint",
            "setfinish", "setfall", "clear", "reload");

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;
    private final ParkourListener listener;
    private final ParkourTopMenu topMenu;

    public ParkourCommand(CrystalParkourPlugin plugin, CrystalCore crystal,
                          ParkourListener listener, ParkourTopMenu topMenu) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.listener = listener;
        this.topMenu = topMenu;
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
            case "top" -> topMenu.open(player);
            case "reset", "sair" -> listener.exitRun(player);
            case "admin" -> handleAdmin(player, args);
            default -> help(player);
        }
        return true;
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase();
        switch (sub) {
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
            default -> adminHelp(player);
        }
    }

    /** Admin helper: mutate the central parkour config and persist it (hot-reloads). */
    private void admin(Player player, Consumer<Map<String, Object>> mutator, String okMsg) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> cfg = new java.util.HashMap<>(
                    crystal.configProvider().get(CrystalParkourPlugin.CONFIG_KEY).config());
            cfg.put("world", player.getWorld().getName());
            cfg.putIfAbsent("fallY", Math.round(player.getLocation().getY() - 5));
            mutator.accept(cfg);
            crystal.backend().putConfig(CrystalParkourPlugin.CONFIG_KEY, cfg);
            player.sendMessage(Component.text("Parkour: " + okMsg + ".", NamedTextColor.GREEN));
        });
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/parkour [top|reset]", NamedTextColor.GRAY));
        if (player.hasPermission(ADMIN_PERM)) {
            adminHelp(player);
        }
    }

    private void adminHelp(Player player) {
        player.sendMessage(Component.text(
                "/parkour admin [setspawn|setstart|setcheckpoint|removecheckpoint|setfinish|setfall|clear|reload]",
                NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERM);
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("top", "reset"));
            if (admin) {
                base.add("admin");
            }
            return filter(base, args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0]) && admin) {
            return filter(ADMIN_SUBS, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
