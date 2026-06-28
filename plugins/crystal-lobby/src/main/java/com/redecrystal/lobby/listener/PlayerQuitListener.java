package com.redecrystal.lobby.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import java.util.Map;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** On quit: drop the player from Redis presence and publish the disconnect. */
public final class PlayerQuitListener implements Listener {

    private final CrystalCore crystal;

    public PlayerQuitListener(CrystalCore crystal) {
        this.crystal = crystal;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        crystal.redis().removeOnlinePlayer(uuid);
        crystal.kafka().publish(KafkaTopics.PLAYER_DISCONNECTED, uuid, Map.of(
                "player", event.getPlayer().getName(), "uuid", uuid, "server", crystal.config().serverId()));
    }
}
