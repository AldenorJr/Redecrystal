package com.redecrystal.tab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Renders the tab header/footer from MiniMessage templates (with {placeholders})
 * and the per-player list entry name from the player's chat-role tag (prefix +
 * colored name), so the tag shown in the tab matches the one shown in chat.
 */
public final class TabRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final String networkName;
    private final String serverId;

    public TabRenderer(String networkName, String serverId) {
        this.networkName = networkName;
        this.serverId = serverId;
    }

    /**
     * Push header/footer (per-player so {ping} is correct) and the list name
     * built from the resolved role's {@code prefix} + name (colored by {@code nameColor}).
     */
    public void apply(Player player, String headerTemplate, String footerTemplate, int online, int max,
                      boolean prefixInTab, String cargoPrefix, String nameColor) {
        Component header = MM.deserialize(fill(headerTemplate, player, online, max));
        Component footer = MM.deserialize(fill(footerTemplate, player, online, max));
        player.sendPlayerListHeaderAndFooter(header, footer);

        if (prefixInTab) {
            Component name = parse((nameColor == null ? "" : nameColor) + player.getName());
            if (cargoPrefix != null && !cargoPrefix.isEmpty()) {
                name = parse(cargoPrefix).append(Component.space()).append(name);
            }
            player.playerListName(name);
        }
    }

    /** Parse a MiniMessage string, falling back to legacy '&' codes if present. */
    private static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LEGACY.deserialize(raw.replace('§', '&'));
        }
        return MM.deserialize(raw);
    }

    private String fill(String template, Player player, int online, int max) {
        if (template == null) {
            return "";
        }
        return template
                .replace("{player}", player.getName())
                .replace("{online}", Integer.toString(online))
                .replace("{max}", Integer.toString(max))
                .replace("{ping}", Integer.toString(player.getPing()))
                .replace("{network}", networkName)
                .replace("{server}", serverId);
    }
}
