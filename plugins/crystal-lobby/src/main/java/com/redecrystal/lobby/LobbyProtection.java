package com.redecrystal.lobby;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Lobby protection: no damage, no hunger, no death. Falling off the hub into the
 * void teleports the player back to the configured lobby spawn instead of killing
 * them. The spawn + void threshold come from {@link CrystalLobbyPlugin} (central
 * config, hot-reloaded).
 */
public final class LobbyProtection implements Listener {

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

    /** Void rescue: below the threshold → back to spawn (no death). */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getY() >= plugin.getVoidY()) {
            return;
        }
        Location spawn = plugin.getSpawn();
        if (spawn != null) {
            event.getPlayer().teleport(spawn);
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
}
