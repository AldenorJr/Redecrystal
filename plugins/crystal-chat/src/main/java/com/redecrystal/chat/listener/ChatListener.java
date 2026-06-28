package com.redecrystal.chat.listener;

import com.redecrystal.chat.ChatService;
import com.redecrystal.chat.CrystalChatPlugin;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.messaging.KafkaTopics;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Captures local chat and fans it out over the {@code player-chat} topic instead
 * of letting Bukkit broadcast it. The sender's tag is resolved here (the only
 * server that can read their permissions) and carried in the payload; the line is
 * also persisted to the backend chat history off-thread.
 */
public final class ChatListener implements Listener {

    private final CrystalChatPlugin plugin;
    private final CrystalCore crystal;
    private final ChatService chat;

    public ChatListener(CrystalChatPlugin plugin, CrystalCore crystal, ChatService chat) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.chat = chat;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
        String clean = chat.censor(PlainTextComponentSerializer.plainText().serialize(event.message()));
        // Resolve the sender's tag here (this is the only server that can read the
        // player's LuckPerms permissions); carry it in the payload so every server
        // renders the same prefix/color regardless of where the player is.
        // An admin override wins over the permission-based resolution; fails open.
        String overrideId = TagOverrides.read(crystal.redis(), event.getPlayer().getUniqueId());
        ChatService.Tag tag = chat.resolveTag(event.getPlayer(), overrideId);
        crystal.kafka().publish(KafkaTopics.PLAYER_CHAT, event.getPlayer().getUniqueId().toString(), Map.of(
                "scope", "global",
                "player", event.getPlayer().getName(),
                "message", clean,
                "server", crystal.config().serverId(),
                "prefix", tag.prefix(),
                "nameColor", tag.nameColor()));

        // Persist to the backend chat history (+ message counter), off-thread.
        final String uuid = event.getPlayer().getUniqueId().toString();
        final String name = event.getPlayer().getName();
        final String server = crystal.config().serverId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                crystal.backend().recordMessage(uuid, name, server, "global", null, clean);
            } catch (Exception e) {
                plugin.getLogger().warning("recordMessage (global) falhou: " + e.getMessage());
            }
        });
    }
}
