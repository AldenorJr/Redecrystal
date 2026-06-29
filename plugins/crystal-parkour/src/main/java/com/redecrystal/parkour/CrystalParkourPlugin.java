package com.redecrystal.parkour;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.parkour.commands.ParkourCommand;
import com.redecrystal.parkour.listener.ParkourListener;
import com.redecrystal.parkour.menu.ParkourTopMenu;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-lobby parkour. Boots the SDK, builds the course from the centralized
 * {@code parkour} config, and wires up the run listener (see {@code listener/})
 * and the {@code parkour} command (see {@code commands/}). This class only
 * boots, registers and owns the course/hologram wiring — the run behaviour lives
 * in {@link ParkourListener} and the command UX in {@link ParkourCommand}.
 *
 * <p>The whole course is command-defined and lives in config, so it replicates
 * to every (identical) lobby like the spawn: {@code /parkour setspawn} (compass
 * arrival), {@code setstart} (start line / iron plate), {@code setcheckpoint}
 * (each checkpoint) and {@code setfinish}. A floating hologram marks every point;
 * detection is by proximity to those points.
 */
public final class CrystalParkourPlugin extends JavaPlugin {

    public static final String CONFIG_KEY = "parkour";

    /** Plate (and themed support block) placed under each point's hologram. */
    private static final Material START_PLATE = Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    private static final Material START_SUPPORT = Material.IRON_BLOCK;
    private static final Material CHECKPOINT_PLATE = Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
    private static final Material CHECKPOINT_SUPPORT = Material.GOLD_BLOCK;
    private static final Material FINISH_PLATE = Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;
    private static final Material FINISH_SUPPORT = Material.DIAMOND_BLOCK;

    private CrystalCore crystal;
    private volatile ParkourCourse course;
    private ParkourHologram hologram;
    private ParkourListener listener;
    private ParkourTopMenu topMenu;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        refreshCourse(crystal.configProvider().get(CONFIG_KEY).config());
        crystal.configProvider().onChange(CONFIG_KEY, cfg -> {
            refreshCourse(cfg.config());
            Bukkit.getScheduler().runTask(this, this::rebuildHolograms); // config events are off-thread
        });

        PluginManager pm = getServer().getPluginManager();
        this.listener = new ParkourListener(this, crystal);
        pm.registerEvents(listener, this);
        this.topMenu = new ParkourTopMenu(this, crystal);
        pm.registerEvents(topMenu, this);
        ParkourCommand parkourCmd = new ParkourCommand(this, crystal, listener, topMenu);
        getCommand("parkour").setExecutor(parkourCmd);
        getCommand("parkour").setTabCompleter(parkourCmd);

        this.hologram = new ParkourHologram(this);
        // Defer until the world is loaded; rebuildHolograms clears orphans first.
        Bukkit.getScheduler().runTaskLater(this, this::rebuildHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshHologram, 200L, 600L);

        getLogger().info("CrystalParkour enabled (course "
                + (course.isPlayable() ? "loaded)" : "not configured yet)"));
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.shutdown(); // restore anyone still running
        }
        if (hologram != null) {
            hologram.remove();
        }
        if (crystal != null) {
            crystal.close();
        }
    }

    /** Rebuild the in-memory course from a raw config map. */
    public void refreshCourse(Map<String, Object> cfg) {
        this.course = ParkourCourse.fromConfig(cfg);
    }

    /** The current course; read by the listener and command. */
    public ParkourCourse course() {
        return course;
    }

    /** The leaderboard GUI, opened by {@code /parkour top}. */
    public ParkourTopMenu topMenu() {
        return topMenu;
    }

    // ── holograms (one over every point) ──

    public void rebuildHolograms() {
        if (hologram == null) {
            return;
        }
        hologram.clearAll();
        ParkourCourse c = course;
        if (c == null) {
            return;
        }
        // Each configured point gets its plate (+ themed support) and a hologram.
        if (c.hasStart()) {
            Location s = c.startLocation();
            placePlate(s, START_PLATE, START_SUPPORT);
            hologram.addLabel(above(s), startLabel());
        }
        for (int i = 0; i < c.checkpointCount(); i++) {
            Location cp = c.checkpointLocation(i);
            placePlate(cp, CHECKPOINT_PLATE, CHECKPOINT_SUPPORT);
            hologram.addLabel(above(cp), checkpointLabel(i + 1));
        }
        if (c.hasFinishPoint()) {
            Location f = c.finishLocation();
            placePlate(f, FINISH_PLATE, FINISH_SUPPORT);
            hologram.setFinish(above(f), finishLabel(null));
        }
        refreshHologram();
    }

    /** Place a pressure plate at the point with a themed solid block beneath it. */
    private void placePlate(Location point, Material plate, Material support) {
        World w = point.getWorld();
        if (w == null) {
            return;
        }
        int x = point.getBlockX();
        int y = point.getBlockY();
        int z = point.getBlockZ();
        w.getBlockAt(x, y - 1, z).setType(support, false);
        w.getBlockAt(x, y, z).setType(plate, false);
    }

    public void refreshHologram() {
        if (hologram == null) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            final List<ParkourEntry> top;
            try {
                top = crystal.backend().parkourTop(3);
            } catch (Exception e) {
                return; // backend down → keep the current text
            }
            getServer().getScheduler().runTask(this, () -> hologram.refreshFinish(finishLabel(top)));
        });
    }

    private static Location above(Location l) {
        return l.clone().add(0.0, 1.3, 0.0); // float a bit above the plate
    }

    private Component startLabel() {
        return Component.text("INÍCIO", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Pise para começar", NamedTextColor.GRAY));
    }

    private Component checkpointLabel(int number) {
        return Component.text("CHECKPOINT " + number, NamedTextColor.GOLD)
            .append(Component.newline())
            .append(Component.text(number, NamedTextColor.WHITE));
    }

    private Component finishLabel(List<ParkourEntry> top) {
        Component head = Component.text("✦ CHEGADA ✦", NamedTextColor.GOLD, TextDecoration.BOLD);
        if (top == null || top.isEmpty()) {
            return head.append(Component.newline())
                    .append(Component.text("Seja o primeiro a terminar!", NamedTextColor.GRAY));
        }
        ParkourEntry best = top.get(0);
        return head.append(Component.newline())
                .append(Component.text("Recorde: ", NamedTextColor.GRAY))
                .append(Component.text(best.username() + " ", NamedTextColor.WHITE))
                .append(Component.text(formatTime(best.timeMs()), NamedTextColor.YELLOW));
    }

    /** Shared run-time formatting (used by the listener, command and holograms). */
    public static String formatTime(long ms) {
        return String.format("%.2fs", ms / 1000.0);
    }
}
