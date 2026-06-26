package com.redecrystal.chat;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.core.messaging.KafkaTopics;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network-wide chat over the {@code player-chat} topic. Global messages are
 * fanned out to everyone; private messages ({@code /tell}) are scoped to a target
 * and delivered only on the server where that player is online. Banned words come
 * from the centralized {@code chat} config (hot-reload). A per-player toggle
 * ({@code tells_disabled} in Redis) blocks incoming tells network-wide.
 */
public final class CrystalChatPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "chat";
    private static final String TELLS_DISABLED = "tells_disabled";
    private static final String DEFAULT_FORMAT = "<prefix> <player_name><gray>:</gray> <message>";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private CrystalCore crystal;
    /** Last person each local player exchanged a tell with, for /r. */
    private final Map<UUID, String> lastConversation = new ConcurrentHashMap<>();

    /** Roles sorted by weight (desc); highest match wins. Hot-reloaded from config. */
    private volatile List<Role> roles = List.of();
    private volatile String chatFormat = DEFAULT_FORMAT;

    /** A chat tag/cargo loaded from the centralized {@code chat} config. */
    private record Role(String id, String permission, int weight, String prefix, String nameColor) { }

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(CONFIG_KEY);
        applyConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, this::applyConfig);

        crystal.events().on(KafkaTopics.PLAYER_CHAT, event -> {
            String scope = event.get("scope");
            if ("tell".equals(scope)) {
                getServer().getScheduler().runTask(this, () -> deliverTell(event.get("from"),
                        event.get("targetUuid"), event.get("message")));
            } else {
                String server = event.get("server");
                String player = event.get("player");
                String message = event.get("message");
                String prefix = event.get("prefix");
                String nameColor = event.get("nameColor");
                if (player != null && message != null) {
                    getServer().getScheduler().runTask(this,
                            () -> broadcast(server, player, message, prefix, nameColor));
                }
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalChat enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    // ── global chat ──

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
        String clean = censor(PlainTextComponentSerializer.plainText().serialize(event.message()));
        // Resolve the sender's tag here (this is the only server that can read the
        // player's LuckPerms permissions); carry it in the payload so every server
        // renders the same prefix/color regardless of where the player is.
        Role role = resolveRole(event.getPlayer());
        crystal.kafka().publish(KafkaTopics.PLAYER_CHAT, event.getPlayer().getUniqueId().toString(), Map.of(
                "scope", "global",
                "player", event.getPlayer().getName(),
                "message", clean,
                "server", crystal.config().serverId(),
                "prefix", role == null ? "" : role.prefix(),
                "nameColor", role == null ? "" : role.nameColor()));
    }

    private void broadcast(String server, String player, String message, String prefix, String nameColor) {
        Component line = MINI.deserialize(
                chatFormat,
                Placeholder.component("prefix", parse(prefix)),
                Placeholder.component("player_name", parse((nameColor == null ? "" : nameColor) + player)),
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("server", server == null ? "" : server),
                Placeholder.unparsed("message", message));
        getServer().sendMessage(line);
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
        getLogger().info("CrystalChat: " + this.roles.size() + " cargo(s) carregado(s) para o chat.");
    }

    /** Highest-weight role whose permission the player has, or {@code null}. */
    private Role resolveRole(Player player) {
        for (Role role : roles) {
            if (player.hasPermission(role.permission())) {
                return role;
            }
        }
        return null;
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

    // ── private messages ──

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        switch (command.getName().toLowerCase()) {
            case "tell" -> {
                if (args.length < 2) {
                    p.sendMessage(Component.text("Uso: /tell <jogador> <mensagem>", NamedTextColor.GRAY));
                } else {
                    sendTell(p, args[0], String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                }
            }
            case "r" -> {
                String target = lastConversation.get(p.getUniqueId());
                if (target == null) {
                    p.sendMessage(Component.text("Ninguém para responder.", NamedTextColor.RED));
                } else if (args.length == 0) {
                    p.sendMessage(Component.text("Uso: /r <mensagem>", NamedTextColor.GRAY));
                } else {
                    sendTell(p, target, String.join(" ", args));
                }
            }
            case "telltoggle" -> toggleTells(p);
            default -> { }
        }
        return true;
    }

    private void sendTell(Player from, String targetName, String rawMessage) {
        String message = censor(rawMessage);
        UUID targetUuid = offlineUuid(targetName);
        if (targetUuid.equals(from.getUniqueId())) {
            from.sendMessage(Component.text("Você não pode mandar mensagem para si mesmo.", NamedTextColor.RED));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
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
            from.sendMessage(Component.text("você → " + targetName + ": ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(message, NamedTextColor.GRAY)));
            lastConversation.put(from.getUniqueId(), targetName);
        });
    }

    private void deliverTell(String from, String targetUuid, String message) {
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

    private void toggleTells(Player p) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
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

    // ── moderation ──

    private String censor(String message) {
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
