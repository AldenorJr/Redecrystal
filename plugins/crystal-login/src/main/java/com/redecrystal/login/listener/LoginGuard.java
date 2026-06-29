package com.redecrystal.login.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.login.CrystalLoginPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Freezes everything a player can do on the login server until they authenticate:
 * no movement beyond a leash, no blocks, no damage/hunger, no chat, no item
 * handling. Also rate-limits connections per IP as a basic anti-bot measure.
 *
 * <p>The login world is a void canvas with its spawn set by crystal-worldinit.
 */
public final class LoginGuard implements Listener {

    /** Horizontal leash radius from spawn, squared (200 blocks). */
    private static final double MAX_RADIUS_SQ = 200.0 * 200.0;

    private static final String ATTEMPTS_PREFIX = "login_ip_attempts:";
    private static final int MAX_CONNECTS_PER_WINDOW = 8;
    private static final Duration ATTEMPTS_WINDOW = Duration.ofSeconds(30);

    private final CrystalLoginPlugin plugin;
    private final CrystalCore crystal;

    public LoginGuard(CrystalLoginPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    private boolean locked(Player p) {
        return !plugin.isAuthenticated(p.getUniqueId()) && !plugin.isEditing(p.getUniqueId());
    }

    /** Anti-bot: cap connections per IP in a short window. Runs off the main thread. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            String key = ATTEMPTS_PREFIX + event.getAddress().getHostAddress();
            long count = crystal.redis().incr(key);
            if (count == 1) {
                crystal.redis().expire(key, ATTEMPTS_WINDOW);
            }
            if (count > MAX_CONNECTS_PER_WINDOW) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Muitas tentativas de conexão. Aguarde alguns segundos.", NamedTextColor.RED));
            }
        } catch (Exception e) {
            // Never block a legitimate login because Redis hiccuped — fail open.
            plugin.getLogger().warning("Anti-bot check skipped: " + e);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        // The login server is auth-only; no chat at all.
        event.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!locked(event.getPlayer())) {
            return; // staff in edit mode (or authenticated) flies freely
        }
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

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && locked(p)) {
            event.setCancelled(true);
        }
    }
}
