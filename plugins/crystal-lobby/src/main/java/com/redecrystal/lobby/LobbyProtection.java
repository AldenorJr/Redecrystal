package com.redecrystal.lobby;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Lobby protection: no damage, no hunger, no death, no block interaction, and a
 * 200-block boundary. Falling into the void or wandering past the boundary
 * teleports the player back to the configured lobby spawn instead of killing
 * them. The spawn + void threshold come from {@link CrystalLobbyPlugin}
 * (central config, hot-reloaded).
 */
public final class LobbyProtection implements Listener {

    /** Horizontal leash radius from spawn, squared (200 blocks). */
    private static final double MAX_RADIUS_SQ = 200.0 * 200.0;

    private final CrystalLobbyPlugin plugin;

    public LobbyProtection(CrystalLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cancel ALL damage to players (fall, fire, drowning, PvP, …). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** No hunger in the lobby. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
        if (event.getEntity() instanceof Player player) {
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
    }

    /**
     * Void rescue + boundary. Only runs when the player crosses a block
     * boundary (cheap). Below the void threshold OR past the 200-block
     * horizontal radius → back to spawn (no death).
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Location spawn = plugin.getSpawn();
        if (spawn == null) {
            return;
        }
        if (to.getY() < plugin.getVoidY()) {
            event.getPlayer().teleport(spawn);
            return;
        }
        if (spawn.getWorld() != null && spawn.getWorld().equals(to.getWorld())) {
            double dx = to.getX() - spawn.getX();
            double dz = to.getZ() - spawn.getZ();
            if (dx * dx + dz * dz > MAX_RADIUS_SQ) {
                event.getPlayer().teleport(spawn);
            }
        }
    }

    /** Belt-and-suspenders: if a death ever slips through, respawn at the lobby. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = plugin.getSpawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    // ── block interaction is fully blocked in the lobby ──

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

    /** Cancel any interaction with a block (buttons, doors, chests, plates…). */
    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }
}
