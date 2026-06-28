package com.redecrystal.parkour;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages the parkour's floating labels — one {@link TextDisplay} over each
 * plate (start / every checkpoint / finish). All are tagged so a crash/reload
 * never leaves a duplicate behind (mirrors the pet cleanup in the lobby plugin).
 * The finish display is tracked separately so its leaderboard text can refresh.
 */
final class ParkourHologram {

    static final String HOLO_TAG = "crystal_parkour_holo";

    private final JavaPlugin plugin;
    private final List<TextDisplay> displays = new ArrayList<>();
    private TextDisplay finish;

    ParkourHologram(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Remove every parkour hologram (tracked + any orphan from a previous run). */
    void clearAll() {
        for (TextDisplay d : displays) {
            if (d != null && !d.isDead()) {
                d.remove();
            }
        }
        displays.clear();
        finish = null;
        removeOrphans();
    }

    void removeOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(HOLO_TAG)) {
                    e.remove();
                }
            }
        }
    }

    /** A plain label (start or checkpoint). */
    void addLabel(Location at, Component text) {
        TextDisplay d = spawn(at, text);
        if (d != null) {
            displays.add(d);
        }
    }

    /** The finish label, tracked so {@link #refreshFinish} can update it. */
    void setFinish(Location at, Component text) {
        TextDisplay d = spawn(at, text);
        if (d != null) {
            displays.add(d);
            finish = d;
        }
    }

    void refreshFinish(Component text) {
        if (finish != null && finish.isValid()) {
            finish.text(text);
        }
    }

    void remove() {
        clearAll();
    }

    private TextDisplay spawn(Location at, Component text) {
        World w = at.getWorld();
        if (w == null) {
            return null;
        }
        return w.spawn(at, TextDisplay.class, td -> {
            td.addScoreboardTag(HOLO_TAG);
            td.setPersistent(false); // we respawn on enable; never save to the world
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.text(text);
        });
    }
}
