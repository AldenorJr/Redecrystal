package com.redecrystal.profile.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.profile.CrystalProfilePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Records every command a player runs into the network-wide activity trail. The
 * write goes through the backend (HTTP), off the main thread.
 */
public final class CommandActivityListener implements Listener {

    private final CrystalProfilePlugin plugin;
    private final CrystalCore crystal;

    public CommandActivityListener(CrystalProfilePlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    private String server() {
        return crystal.config().serverId();
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String cmd = event.getMessage();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                crystal.backend().recordActivity(uuid, name, "COMMAND", cmd, server());
            } catch (Exception e) {
                plugin.getLogger().warning("Command record failed: " + e);
            }
        });
    }
}
