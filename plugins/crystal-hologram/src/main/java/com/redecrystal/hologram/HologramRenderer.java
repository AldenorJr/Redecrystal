package com.redecrystal.hologram;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spawns one {@link TextDisplay} per network hologram. Everything is tagged so a
 * crash/reload never leaves a duplicate behind (mirrors the parkour holograms and
 * the lobby pets). Holograms whose world is absent on this server are skipped, so
 * the same config runs safely on any server type.
 */
final class HologramRenderer {

    static final String HOLO_TAG = "crystal_holo";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final String serverType;
    private final List<TextDisplay> displays = new ArrayList<>();

    HologramRenderer(JavaPlugin plugin, String serverType) {
        this.plugin = plugin;
        this.serverType = serverType;
    }

    /** Clear everything and respawn the defs scoped to THIS server type. Main thread. */
    void render(List<HologramDef> defs) {
        clearAll();
        for (HologramDef def : defs) {
            if (!serverType.equals(def.type())) {
                continue; // belongs to another server type (or legacy/untyped) — not ours
            }
            World w = plugin.getServer().getWorld(def.world());
            if (w == null) {
                plugin.getLogger().warning("Hologram '" + def.id()
                        + "' skipped: world '" + def.world() + "' not on this server.");
                continue;
            }
            spawn(new Location(w, def.x(), def.y(), def.z()), text(def.lines()));
        }
    }

    /** Remove every hologram (tracked + any orphan from a previous run). */
    void clearAll() {
        for (TextDisplay d : displays) {
            if (d != null && !d.isDead()) {
                d.remove();
            }
        }
        displays.clear();
        removeOrphans();
    }

    private void removeOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(HOLO_TAG)) {
                    e.remove();
                }
            }
        }
    }

    private void spawn(Location at, Component text) {
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        displays.add(w.spawn(at, TextDisplay.class, td -> {
            td.addScoreboardTag(HOLO_TAG);
            td.setPersistent(false); // respawned on enable / hot-reload; never saved
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.text(text);
        }));
    }

    /** Join the lines (legacy '&' colours) into one multi-line component. */
    private static Component text(List<String> lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(LEGACY.deserialize(lines.get(i)));
        }
        return out;
    }
}
