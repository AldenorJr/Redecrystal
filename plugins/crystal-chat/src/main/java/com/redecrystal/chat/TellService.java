package com.redecrystal.chat;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Private messages over the {@code player-chat} topic. A tell is published to the
 * target's partition and delivered only on the server where they are online. A
 * per-player toggle ({@code tells_disabled} in Redis) blocks incoming tells
 * network-wide, and the last conversation partner is tracked locally for {@code /r}.
 */
public final class TellService {

    private static final String TELLS_DISABLED = "tells_disabled";

    private final CrystalChatPlugin plugin;
    private final CrystalCore crystal;
    private final ChatService chat;

    /** Last person each local player exchanged a tell with, for /r. */
    private final Map<UUID, String> lastConversation = new ConcurrentHashMap<>();

    public TellService(CrystalChatPlugin plugin, CrystalCore crystal, ChatService chat) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.chat = chat;
    }

    /** Last player the given player exchanged a tell with, or {@code null}. */
    public String lastTarget(UUID player) {
        return lastConversation.get(player);
    }

    public void sendTell(Player from, String targetName, String rawMessage) {
        String message = chat.censor(rawMessage);
        UUID targetUuid = offlineUuid(targetName);
        if (targetUuid.equals(from.getUniqueId())) {
            from.sendMessage(Component.text("Você não pode mandar mensagem para si mesmo.", NamedTextColor.RED));
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!crystal.redis().isOnline(targetUuid.toString())) {
                from.sendMessage(Component.text(targetName + " está offline.", NamedTextColor.RED));
                return;
            }
            if (crystal.redis().sismember(TELLS_DISABLED, targetUuid.toString())) {
                from.sendMessage(Component.text(targetName + " não está recebendo mensagens.", NamedTextColor.RED));
                return;
            }
            crystal.kafka().publish(KafkaTopics.PLAYER_CHAT, targetUuid.toString(), Map.of(
                    "scope", "tell",
                    "from", from.getName(),
                    "fromUuid", from.getUniqueId().toString(),
                    "targetUuid", targetUuid.toString(),
                    "message", message));
            try {
                crystal.backend().recordMessage(from.getUniqueId().toString(), from.getName(),
                        crystal.config().serverId(), "tell", targetName, message);
            } catch (Exception e) {
                plugin.getLogger().warning("recordMessage (tell) falhou: " + e.getMessage());
            }
            from.sendMessage(Component.text("você → " + targetName + ": ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(message, NamedTextColor.GRAY)));
            lastConversation.put(from.getUniqueId(), targetName);
        });
    }

    /** Deliver an incoming tell to the target if they are online on this server. */
    public void deliverTell(String from, String targetUuid, String message) {
        if (targetUuid == null || from == null) {
            return;
        }
        Player target = Bukkit.getPlayer(UUID.fromString(targetUuid));
        if (target == null || !target.isOnline()) {
            return; // not on this server
        }
        target.sendMessage(Component.text(from + " → você: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(message, NamedTextColor.GRAY)));
        lastConversation.put(target.getUniqueId(), from);
    }

    public void toggleTells(Player p) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String uuid = p.getUniqueId().toString();
            boolean disabled = crystal.redis().sismember(TELLS_DISABLED, uuid);
            if (disabled) {
                crystal.redis().srem(TELLS_DISABLED, uuid);
                p.sendMessage(Component.text("Você voltou a receber mensagens privadas.", NamedTextColor.GREEN));
            } else {
                crystal.redis().sadd(TELLS_DISABLED, uuid);
                p.sendMessage(Component.text("Você não receberá mais mensagens privadas.", NamedTextColor.YELLOW));
            }
        });
    }

    /** Offline-mode UUID derived from a name (matches the player's own UUID). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
