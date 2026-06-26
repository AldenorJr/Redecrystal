package com.redecrystal.tab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Renders the tab header/footer from MiniMessage templates (with {placeholders})
 * and the per-player list entry name from the LuckPerms prefix.
 */
public final class TabRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final LuckPermsHook luckPerms;
    private final String networkName;
    private final String serverId;

    public TabRenderer(LuckPermsHook luckPerms, String networkName, String serverId) {
        this.luckPerms = luckPerms;
        this.networkName = networkName;
        this.serverId = serverId;
    }

    /** Push header/footer (per-player so {ping} is correct) and the list name. */
    public void apply(Player player, String headerTemplate, String footerTemplate, int online, int max, boolean prefixInTab) {
        Component header = MM.deserialize(fill(headerTemplate, player, online, max));
        Component footer = MM.deserialize(fill(footerTemplate, player, online, max));
        player.sendPlayerListHeaderAndFooter(header, footer);

        if (prefixInTab) {
            String prefix = luckPerms.prefix(player).replace('§', '&');
            Component name = LEGACY.deserialize(prefix).append(Component.text(player.getName()));
            player.playerListName(name);
        }
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
