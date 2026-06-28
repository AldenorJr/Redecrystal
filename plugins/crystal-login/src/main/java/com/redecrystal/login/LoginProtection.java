package com.redecrystal.login;

import org.bukkit.Location;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Login-server guard: cancels all block interaction and pulls players back to
 * the world spawn if they wander beyond a 200-block (horizontal) radius. The
 * login world is a void canvas with its spawn set by crystal-worldinit.
 */
public final class LoginProtection implements Listener {

    /** Horizontal leash radius from spawn, squared (200 blocks). */
    private static final double MAX_RADIUS_SQ = 200.0 * 200.0;

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (to.getWorld() == null) {
            return;
        }
        Location spawn = to.getWorld().getSpawnLocation();
        double dx = to.getX() - spawn.getX();
        double dz = to.getZ() - spawn.getZ();
        if (dx * dx + dz * dz > MAX_RADIUS_SQ) {
            event.getPlayer().teleport(spawn);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

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
