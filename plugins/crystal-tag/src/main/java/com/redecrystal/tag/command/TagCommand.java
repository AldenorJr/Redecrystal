package com.redecrystal.tag.command;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.tag.menu.TagEditorMenu;
import com.redecrystal.tag.menu.TagSelectorMenu;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tag} — admin-only. No args opens the selector for yourself;
 * {@code /tag <jogador>} targets another player; {@code /tag editar} opens the
 * cargo editor. Gated by {@code crystal.tag.admin}.
 */
public final class TagCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.tag.admin";

    private final TagSelectorMenu selector;
    private final TagEditorMenu editor;

    public TagCommand(CrystalCore crystal, TagSelectorMenu selector, TagEditorMenu editor) {
        this.selector = selector;
        this.editor = editor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (!admin.hasPermission(ADMIN_PERM)) {
            admin.sendMessage(Component.text("Você não tem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            selector.open(admin, admin.getUniqueId(), admin.getName());
            return true;
        }
        if (args[0].equalsIgnoreCase("editar")) {
            editor.open(admin);
            return true;
        }
        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        UUID target = online != null ? online.getUniqueId() : offlineUuid(targetName);
        selector.open(admin, target, online != null ? online.getName() : targetName);
        return true;
    }

    /** Offline-mode UUID derived from a name (matches the player's own UUID). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
