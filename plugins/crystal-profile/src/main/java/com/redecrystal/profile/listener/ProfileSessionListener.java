package com.redecrystal.profile.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.profile.CrystalProfilePlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Session lifecycle: ensures the profile on join and banks the session play time
 * on quit, recording the matching JOIN/QUIT activity. Join and quit share the
 * {@code joinedAt} timing map, so they live in one cohesive listener. All
 * persistence goes through the backend (HTTP), off the main thread.
 */
public final class ProfileSessionListener implements Listener {

    private final CrystalProfilePlugin plugin;
    private final CrystalCore crystal;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    public ProfileSessionListener(CrystalProfilePlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    private String server() {
        return crystal.config().serverId();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        joinedAt.put(uuid, System.currentTimeMillis());
        String name = player.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                crystal.backend().ensureProfile(uuid.toString(), name);
                crystal.backend().recordActivity(uuid.toString(), name, "JOIN", null, server());
            } catch (Exception e) {
                plugin.getLogger().warning("Profile join failed for " + name + ": " + e);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        Long start = joinedAt.remove(uuid);
        long seconds = start == null ? 0 : Math.max(0, (System.currentTimeMillis() - start) / 1000);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (seconds > 0) {
                    crystal.backend().addStats(uuid.toString(), 0, 0, seconds);
                }
                crystal.backend().recordActivity(uuid.toString(), name, "QUIT",
                        seconds > 0 ? "sessão: " + seconds + "s" : null, server());
            } catch (Exception e) {
                plugin.getLogger().warning("Profile quit save failed: " + e);
            }
        });
    }
}
