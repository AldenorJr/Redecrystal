package com.redecrystal.login.listener;

import com.redecrystal.login.CrystalLoginPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Clears the player's local login state on quit. Local state only — the proxy's
 * DisconnectEvent revokes the real session, so a player merely moving to a lobby
 * must keep their Redis session intact.
 */
public final class PlayerQuitListener implements Listener {

    private final CrystalLoginPlugin plugin;

    public PlayerQuitListener(CrystalLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null); // login screen is auth-only; no vanilla leave broadcast
        plugin.clearLocalState(event.getPlayer().getUniqueId());
    }
}
