package com.redecrystal.lobby.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import com.redecrystal.lobby.CrystalLobbyPlugin;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * On join: enforce a valid login session, publish presence (Redis + Kafka), put
 * the player in ADVENTURE mode with full health, and send them to the lobby
 * spawn. Defense in depth — the proxy already gates unauthenticated players, but
 * a lobby must never trust a raw connection.
 */
public final class PlayerJoinListener implements Listener {

    private static final String FLY_PERM = "crystal.fly";

    private final CrystalLobbyPlugin plugin;
    private final CrystalCore crystal;

    public PlayerJoinListener(CrystalLobbyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();

        // Fail open on a Redis error so a hiccup can't mass-kick the lobby.
        try {
            if (crystal.redis().get("session:" + uuid) == null) {
                player.kick(Component.text("Sessão inválida — entre pela tela de login.", NamedTextColor.RED));
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Session check failed for " + player.getName() + " (allowing): " + e);
        }

        crystal.redis().addOnlinePlayer(uuid);
        crystal.kafka().publish(KafkaTopics.PLAYER_CONNECTED, uuid, Map.of(
                "player", player.getName(), "uuid", uuid, "server", crystal.config().serverId()));

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        if (player.hasPermission(FLY_PERM)) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        plugin.sendToSpawn(player);
    }
}
