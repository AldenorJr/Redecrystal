package com.redecrystal.tab.listener;

import com.redecrystal.tab.CrystalTabPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * On join: render the tab list for the joining player immediately, so they don't
 * wait for the next periodic refresh. The actual render (config-driven header/
 * footer, role tag, network-wide online count) lives in the plugin, which owns
 * the hot-reloaded state.
 */
public final class PlayerJoinListener implements Listener {

    private final CrystalTabPlugin plugin;

    public PlayerJoinListener(CrystalTabPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.renderOnJoin(event.getPlayer());
    }
}
