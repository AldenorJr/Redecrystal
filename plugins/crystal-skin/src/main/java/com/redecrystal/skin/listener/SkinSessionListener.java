package com.redecrystal.skin.listener;

import com.redecrystal.skin.skin.SkinApplier;
import com.redecrystal.skin.skin.SkinHistory;
import com.redecrystal.skin.store.SkinHistoryStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Session glue for the skin history: loads it on join (and re-applies the most
 * recently used skin), and drops the cached state on quit. Re-apply runs a short
 * delay after the async preload so the texture is in the cache first.
 */
public final class SkinSessionListener implements Listener {

    /** Ticks to wait after join before re-applying, giving the async preload time. */
    private static final long REAPPLY_DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final SkinHistoryStore store;
    private final SkinApplier applier;

    public SkinSessionListener(JavaPlugin plugin, SkinHistoryStore store, SkinApplier applier) {
        this.plugin = plugin;
        this.store = store;
        this.applier = applier;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        store.preload(player.getUniqueId());
        // Restore the last-used skin once the preload has had time to land.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            SkinHistory history = store.get(player.getUniqueId());
            if (!history.isEmpty()) {
                applier.apply(player, history.entries().get(0));
            }
        }, REAPPLY_DELAY_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        store.evict(event.getPlayer().getUniqueId());
    }
}
