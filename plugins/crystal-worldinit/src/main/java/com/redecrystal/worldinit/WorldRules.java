package com.redecrystal.worldinit;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;

/**
 * Locks the hub world into a calm, static state: eternal day (no day/night
 * cycle), clear weather, peaceful difficulty and no natural mob spawning — so no
 * phantoms harass idle players and nothing hostile ever appears. Cosmetic pets
 * are spawned by the lobby plugin via the API (CUSTOM reason) and are passive,
 * so they are unaffected by these rules.
 *
 * <p>Not event-driven (so it lives in the base package, not {@code listener/}):
 * the default hub world is loaded before plugins enable, so {@code WorldLoadEvent}
 * would never fire for it. The plugin instead invokes {@link #lock()} a few ticks
 * after enable, once the world is ready — see {@link CrystalWorldInitPlugin#onEnable()}.
 */
public final class WorldRules {

    private static final long MIDDAY_TICKS = 6000L;

    private final CrystalWorldInitPlugin plugin;
    private final String worldName;

    public WorldRules(CrystalWorldInitPlugin plugin, String worldName) {
        this.plugin = plugin;
        this.worldName = worldName;
    }

    /** Apply the static hub rules to the configured world (idempotent). */
    public void lock() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found for rule lock: " + worldName);
            return;
        }
        world.setDifficulty(Difficulty.PEACEFUL);            // no hostile mobs / phantom damage
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(MIDDAY_TICKS);                         // fixed midday
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
        plugin.getLogger().info("Hub world rules locked: peaceful, eternal day, clear weather, no spawns.");
    }
}
