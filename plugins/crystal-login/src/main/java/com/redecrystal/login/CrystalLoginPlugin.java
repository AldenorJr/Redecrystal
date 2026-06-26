package com.redecrystal.login;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import java.time.Duration;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Login plugin. For this vertical slice it performs offline ("cracked") auth:
 * on join it records a session in Redis and emits {@code player-authenticated}.
 * The proxy (crystal-bungee) consumes that event and routes the player to a
 * lobby — keeping routing on the proxy. Premium auth is layered on later.
 */
public final class CrystalLoginPlugin extends JavaPlugin implements Listener {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload("login");
        crystal.registerThisServer(crystal.configProvider().get("login").integer("maxPlayers", 200));
        crystal.startHeartbeat(() -> getServer().getOnlinePlayers().size(),
                () -> getServer().getTPS()[0]);

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalLogin enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();

        // Offline auth: record a session and announce authentication. The proxy
        // listens for this event and forwards the player to a lobby.
        crystal.redis().setex("session:" + uuid, player.getName(), Duration.ofHours(6));
        crystal.kafka().publish(KafkaTopics.PLAYER_AUTHENTICATED, uuid, Map.of(
                "player", player.getName(),
                "uuid", uuid,
                "premium", false));
        getLogger().info("Authenticated " + player.getName() + "; proxy will route to lobby.");
    }
}
