package com.redecrystal.worldinit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import java.io.File;
import java.io.FileInputStream;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Initializes a void world from a schematic on first startup — headless, via the
 * WorldEdit API (no player required). Configured by env:
 * <ul>
 *   <li>{@code CRYSTAL_WORLD_SCHEMATIC} — path to the .schem/.schematic file</li>
 *   <li>{@code CRYSTAL_WORLD_PASTE} — "x,y,z" paste point (default 0,64,0)</li>
 *   <li>{@code CRYSTAL_WORLD} — world name (default "world")</li>
 * </ul>
 * A marker file makes this run exactly once; the build then persists in the world.
 */
public final class CrystalWorldInitPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Hub world rules — re-applied on every boot (idempotent), independent of
        // the one-time schematic paste. A little after enable so the world is ready.
        Bukkit.getScheduler().runTaskLater(this, this::lockWorld, 40L);

        String schemPath = env("CRYSTAL_WORLD_SCHEMATIC", "");
        if (schemPath.isBlank()) {
            getLogger().info("CRYSTAL_WORLD_SCHEMATIC not set — nothing to initialize.");
            return;
        }
        File marker = new File(getDataFolder(), ".initialized");
        if (marker.exists()) {
            getLogger().info("World already initialized — skipping schematic paste.");
            return;
        }
        // Paste a little after enable so the world + FAWE are fully ready.
        Bukkit.getScheduler().runTaskLater(this, () -> paste(schemPath, marker), 40L);
    }

    /**
     * Lock the hub world into a calm, static state: eternal day (no day/night
     * cycle), clear weather, peaceful difficulty and no natural mob spawning — so
     * no phantoms harass idle players and nothing hostile ever appears. Cosmetic
     * pets are spawned by the lobby plugin via the API (CUSTOM reason) and are
     * passive, so they are unaffected by these rules.
     */
    private void lockWorld() {
        World world = Bukkit.getWorld(env("CRYSTAL_WORLD", "world"));
        if (world == null) {
            getLogger().warning("World not found for rule lock: " + env("CRYSTAL_WORLD", "world"));
            return;
        }
        world.setDifficulty(Difficulty.PEACEFUL);            // no hostile mobs / phantom damage
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(6000L);                                // fixed midday
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);      // never spawn phantoms
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);  // hub has no natural spawns
        // Remove anything hostile that spawned before the rules took effect.
        for (Entity e : world.getEntities()) {
            if (e instanceof Enemy) {
                e.remove();
            }
        }
        getLogger().info("Hub world rules locked: peaceful, eternal day, clear weather, no spawns.");
    }

    private void paste(String schemPath, File marker) {
        try {
            File file = new File(schemPath);
            if (!file.exists()) {
                getLogger().warning("Schematic not found: " + schemPath);
                return;
            }
            World world = Bukkit.getWorld(env("CRYSTAL_WORLD", "world"));
            if (world == null) {
                getLogger().warning("World not found: " + env("CRYSTAL_WORLD", "world"));
                return;
            }
            int[] at = parsePoint(env("CRYSTAL_WORLD_PASTE", "0,64,0"));
            BlockVector3 to = BlockVector3.at(at[0], at[1], at[2]);

            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                getLogger().warning("Unknown schematic format: " + file.getName());
                return;
            }
            Clipboard clipboard;
            try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
                clipboard = reader.read();
            }

            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld).maxBlocks(-1).build()) {
                Operation op = new ClipboardHolder(clipboard)
                        .createPaste(edit)
                        .to(to)
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(op);
            }

            // Spawn at the horizontal centre, just above the build's top. Computed
            // from the clipboard (not getHighestBlockYAt, which can read before the
            // async paste flushes), so it never lands inside a block.
            Region region = clipboard.getRegion();
            BlockVector3 offset = to.subtract(clipboard.getOrigin());
            BlockVector3 min = region.getMinimumPoint().add(offset);
            BlockVector3 max = region.getMaximumPoint().add(offset);
            int sx = (min.getX() + max.getX()) / 2;
            int sz = (min.getZ() + max.getZ()) / 2;
            int sy = max.getY() + 2;
            world.setSpawnLocation(sx, sy, sz);

            marker.getParentFile().mkdirs();
            marker.createNewFile();
            getLogger().info("Pasted schematic " + file.getName() + " at " + at[0] + "," + at[1] + "," + at[2]
                    + "; spawn set to " + sx + "," + sy + "," + sz);
        } catch (Exception e) {
            getLogger().severe("World init failed: " + e);
            e.printStackTrace();
        }
    }

    private static int[] parsePoint(String s) {
        String[] p = s.split(",");
        return new int[]{
                (int) Double.parseDouble(p[0].trim()),
                (int) Double.parseDouble(p[1].trim()),
                (int) Double.parseDouble(p[2].trim())};
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
