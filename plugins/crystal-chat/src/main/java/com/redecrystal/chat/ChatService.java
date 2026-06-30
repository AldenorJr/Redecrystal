package com.redecrystal.chat;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Chat formatting and moderation. Owns the centralized {@code chat} config
 * (hot-reloaded): the message format, the weight-sorted role/tag cache and the
 * banned-words list. The tag is resolved on the player's own server — the only
 * one that can read their LuckPerms permissions — and the resolved prefix/color
 * is carried in the Kafka payload so every server renders the same line.
 */
public final class ChatService {

    private static final String CONFIG_KEY = "chat";
    private static final String DEFAULT_FORMAT = "<prefix> <player_name><gray>:</gray> <message>";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Legacy format/reset codes (&k &l &m &n &o &r) — stripped from player messages. */
    private static final String FORMAT_CODES = "(?i)&[k-or]";

    private final CrystalChatPlugin plugin;
    private final CrystalCore crystal;

    /** Roles sorted by weight (desc); highest match wins. Hot-reloaded from config. */
    private volatile List<Role> roles = List.of();
    private volatile String chatFormat = DEFAULT_FORMAT;

    /** A chat tag/cargo loaded from the centralized {@code chat} config. */
    private record Role(String id, String permission, int weight, String prefix, String nameColor) { }

    /** Resolved prefix/color for a player, empty when no role matches. */
    public record Tag(String prefix, String nameColor) {
        public static final Tag EMPTY = new Tag("", "");
    }

    public ChatService(CrystalChatPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** Preload the chat config, apply it now and react to hot-reloads. */
    public void register() {
        crystal.configProvider().preload(CONFIG_KEY);
        applyConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, this::applyConfig);
    }

    // ── roles / tags ──

    /** Rebuild the role cache and chat format from the centralized config. */
    @SuppressWarnings("unchecked")
    private void applyConfig(RemoteConfig cfg) {
        this.chatFormat = cfg.string("chatFormat", DEFAULT_FORMAT);

        List<Role> loaded = new ArrayList<>();
        if (cfg.value("roles") instanceof Map<?, ?> rolesMap) {
            for (Map.Entry<?, ?> entry : rolesMap.entrySet()) {
                String id = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> data)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) data;
                String permission = m.get("permission") == null ? "tag." + id : String.valueOf(m.get("permission"));
                int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
                String prefix = m.get("prefix") == null ? "" : String.valueOf(m.get("prefix"));
                String nameColor = m.get("nameColor") == null ? "" : String.valueOf(m.get("nameColor"));
                loaded.add(new Role(id, permission, weight, prefix, nameColor));
            }
        }
        loaded.sort(Comparator.comparingInt(Role::weight).reversed());
        this.roles = List.copyOf(loaded);
        plugin.getLogger().info("CrystalChat: " + this.roles.size() + " cargo(s) carregado(s) para o chat.");
    }

    /** Highest-weight tag whose permission the player has, or {@link Tag#EMPTY}. */
    public Tag resolveTag(Player player) {
        for (Role role : roles) {
            if (player.hasPermission(role.permission())) {
                return new Tag(role.prefix(), role.nameColor());
            }
        }
        return Tag.EMPTY;
    }

    /** Highest-weight tag, but an admin override (by cargo id) wins. */
    public Tag resolveTag(Player player, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            for (Role role : roles) {
                if (role.id().equals(overrideId)) {
                    return new Tag(role.prefix(), role.nameColor());
                }
            }
        }
        return resolveTag(player);
    }

    /** Render and broadcast a global chat line on this server. */
    public void broadcast(String server, String player, String message, String prefix,
                          String nameColor, boolean allowColors) {
        Component messageComponent = allowColors ? colorsOnly(message) : Component.text(message);
        Component line = MINI.deserialize(
                chatFormat,
                Placeholder.component("prefix", parse(prefix)),
                Placeholder.component("player_name", parse((nameColor == null ? "" : nameColor) + player)),
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("server", server == null ? "" : server),
                Placeholder.component("message", messageComponent));
        plugin.getServer().sendMessage(line);
    }

    /**
     * Translate ONLY legacy '&' colour codes (&0-&f) in a player's message: strip
     * the section sign and the format/reset codes first, and never interpret
     * MiniMessage (so players can't inject &lt;click&gt;/&lt;hover&gt;/rainbow).
     */
    private static Component colorsOnly(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String stripped = raw.replace("§", "").replaceAll(FORMAT_CODES, "");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(stripped);
    }

    /** Parse a MiniMessage string, falling back to legacy '&' codes if present. */
    private static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§', '&'));
        }
        return MINI.deserialize(raw);
    }

    // ── moderation ──

    /** Replace each configured banned word (case-insensitive) with asterisks. */
    public String censor(String message) {
        RemoteConfig cfg = crystal.configProvider().get(CONFIG_KEY);
        if (!(cfg.value("bannedWords") instanceof List<?> words)) {
            return message;
        }
        String result = message;
        for (Object w : words) {
            String word = String.valueOf(w);
            if (!word.isBlank()) {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "*".repeat(word.length()));
            }
        }
        return result;
    }
}
