package com.redecrystal.parkour;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.core.http.ParkourResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-lobby parkour. The course lives in the centralized {@code parkour} config
 * (configured live with admin commands, hot-reloaded everywhere). Players run
 * start → checkpoints → finish; the time is submitted to the backend, which
 * keeps the best-time leaderboard.
 */
public final class CrystalParkourPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "parkour";
    private static final String ADMIN_PERM = "crystal.parkour.admin";

    private CrystalCore crystal;
    private volatile ParkourCourse course;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    private static final class Run {
        final long startNanos;
        int lastCheckpoint = -1;
        Run(long startNanos) { this.startNanos = startNanos; }
    }

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        refreshCourse(crystal.configProvider().get(CONFIG_KEY).config());
        crystal.configProvider().onChange(CONFIG_KEY, cfg -> refreshCourse(cfg.config()));
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalParkour enabled (course "
                + (course.isPlayable() ? "loaded)" : "not configured yet)"));
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    private void refreshCourse(Map<String, Object> cfg) {
        this.course = ParkourCourse.fromConfig(cfg);
    }

    // ── gameplay ──

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null
                || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // only react on block changes
        }
        ParkourCourse c = course;
        if (c == null || !c.isPlayable()) {
            return;
        }
        Player p = event.getPlayer();
        Run run = runs.get(p.getUniqueId());
        if (run == null) {
            if (c.inStart(to)) {
                runs.put(p.getUniqueId(), new Run(System.nanoTime()));
                p.sendActionBar(Component.text("Vai!", NamedTextColor.GREEN));
            }
            return;
        }
        if (c.belowKillPlane(to)) {
            p.teleport(run.lastCheckpoint >= 0 ? c.checkpointLocation(run.lastCheckpoint) : c.startLocation());
            return;
        }
        int cp = c.checkpointAt(to);
        if (cp > run.lastCheckpoint) {
            run.lastCheckpoint = cp;
            p.sendActionBar(Component.text("Checkpoint " + (cp + 1), NamedTextColor.AQUA));
        }
        if (c.inFinish(to)) {
            finishRun(p, run);
            return;
        }
        p.sendActionBar(Component.text(formatTime((System.nanoTime() - run.startNanos) / 1_000_000), NamedTextColor.YELLOW));
    }

    private void finishRun(Player p, Run run) {
        long ms = (System.nanoTime() - run.startNanos) / 1_000_000;
        runs.remove(p.getUniqueId());
        p.teleport(course.startLocation());
        String uuid = p.getUniqueId().toString();
        String name = p.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                ParkourResult r = crystal.backend().submitParkourTime(uuid, name, ms);
                p.sendMessage(Component.text("Você terminou em ", NamedTextColor.GREEN)
                        .append(Component.text(formatTime(ms), NamedTextColor.YELLOW))
                        .append(Component.text(r.record() ? "  (NOVO RECORDE! #" + r.rank() + ")"
                                : "  (seu recorde: " + formatTime(r.bestTimeMs()) + ", #" + r.rank() + ")",
                                NamedTextColor.GOLD)));
            } catch (Exception e) {
                getLogger().warning("Parkour submit failed: " + e);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
    }

    // ── commands ──

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        String sub = args.length == 0 ? "play" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> startPlaying(player);
            case "top" -> showTop(player);
            case "reset" -> {
                runs.remove(player.getUniqueId());
                player.sendMessage(Component.text("Run cancelada.", NamedTextColor.GRAY));
            }
            case "setstart" -> admin(player, m -> m.put("start", ParkourCourse.startMap(player.getLocation())), "início definido");
            case "setfinish" -> admin(player, m -> m.put("finish", ParkourCourse.pointMap(player.getLocation())), "fim definido");
            case "addcheckpoint" -> admin(player, m -> {
                @SuppressWarnings("unchecked")
                List<Object> cps = new ArrayList<>((List<Object>) m.getOrDefault("checkpoints", new ArrayList<>()));
                cps.add(ParkourCourse.pointMap(player.getLocation()));
                m.put("checkpoints", cps);
            }, "checkpoint adicionado");
            case "clear" -> admin(player, m -> {
                m.remove("start");
                m.remove("finish");
                m.remove("checkpoints");
            }, "curso limpo");
            case "reload" -> {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    refreshCourse(crystal.backend().getConfig(CONFIG_KEY).config());
                    player.sendMessage(Component.text("Curso recarregado.", NamedTextColor.GREEN));
                });
            }
            default -> player.sendMessage(Component.text("/parkour [top|reset|setstart|addcheckpoint|setfinish|clear|reload]", NamedTextColor.GRAY));
        }
        return true;
    }

    private void startPlaying(Player player) {
        ParkourCourse c = course;
        if (c == null || !c.isPlayable()) {
            player.sendMessage(Component.text("O parkour ainda não foi configurado.", NamedTextColor.RED));
            return;
        }
        player.teleport(c.startLocation());
        runs.put(player.getUniqueId(), new Run(System.nanoTime()));
        player.sendActionBar(Component.text("Vai!", NamedTextColor.GREEN));
    }

    private void showTop(Player player) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<ParkourEntry> top = crystal.backend().parkourTop(10);
            player.sendMessage(Component.text("— Ranking Parkour (menor tempo) —", NamedTextColor.GOLD));
            if (top.isEmpty()) {
                player.sendMessage(Component.text("Ninguém completou ainda.", NamedTextColor.GRAY));
            }
            for (ParkourEntry e : top) {
                player.sendMessage(Component.text("#" + e.rank() + " ", NamedTextColor.YELLOW)
                        .append(Component.text(e.username() + " ", NamedTextColor.WHITE))
                        .append(Component.text(formatTime(e.timeMs()), NamedTextColor.AQUA)));
            }
        });
    }

    /** Admin helper: mutate the central parkour config and persist it (hot-reloads). */
    private void admin(Player player, Consumer<Map<String, Object>> mutator, String okMsg) {
        if (!player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
            cfg.put("world", player.getWorld().getName());
            cfg.putIfAbsent("fallY", player.getLocation().getY() - 5);
            cfg.putIfAbsent("radius", 1.5);
            mutator.accept(cfg);
            crystal.backend().putConfig(CONFIG_KEY, cfg);
            player.sendMessage(Component.text("Parkour: " + okMsg + ".", NamedTextColor.GREEN));
        });
    }

    private static String formatTime(long ms) {
        return String.format("%.2fs", ms / 1000.0);
    }
}
