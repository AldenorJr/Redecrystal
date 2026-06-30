package com.redecrystal.login.listener;

import com.redecrystal.login.CrystalLoginPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Filters every command typed before authentication. Only the auth commands
 * ({@code /login}, {@code /registrar}) are honoured, and they are intercepted
 * here rather than registered as real commands so the typed password is never
 * logged, tab-completed or broadcast. Everything else is cancelled with a hint.
 * The actual credential submission is delegated to the plugin.
 */
public final class CommandFilterListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String ADMIN_PERM = "crystal.login.admin";

    private final CrystalLoginPlugin plugin;

    public CommandFilterListener(CrystalLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (plugin.isAuthenticated(player.getUniqueId())) {
            return; // already in; let it through (they are being routed away anyway)
        }
        event.setCancelled(true); // nothing but the auth commands is allowed pre-login

        String[] parts = event.getMessage().trim().split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/login", "/l" -> {
                // Staff subcommands are checked BEFORE treating args as a password and
                // gated by permission. Entering edit mode additionally requires the
                // password (offline-mode: the op permission is keyed to a spoofable
                // username), so a non-admin's password just falls through to auth.
                if (player.hasPermission(ADMIN_PERM)) {
                    String sub = parts.length >= 2 ? parts[1].toLowerCase() : "";
                    switch (sub) {
                        case "manutencao", "manutenção" -> {
                            if (plugin.isEditing(player.getUniqueId())) {
                                plugin.exitEditMode(player); // toggle off; no password needed
                            } else if (parts.length >= 3) {
                                plugin.enterEditMode(player, parts[2]); // verify password first
                            } else {
                                send(player, "<red>Uso: /login manutencao <senha>");
                            }
                            return;
                        }
                        case "setspawn" -> {
                            if (plugin.isEditing(player.getUniqueId())) {
                                plugin.setLoginSpawn(player);
                            } else {
                                send(player, "<red>Entre em modo edição primeiro: <white>/login manutencao <senha>");
                            }
                            return;
                        }
                        default -> { /* fall through to password handling */ }
                    }
                }
                if (parts.length < 2) {
                    send(player, "<red>Uso: /login <senha>");
                    return;
                }
                plugin.authenticate(player, parts[1], false);
            }
            case "/registrar", "/reg" -> {
                if (parts.length < 3) {
                    send(player, "<red>Uso: /registrar <senha> <repita a senha>");
                    return;
                }
                if (!parts[1].equals(parts[2])) {
                    send(player, "<red>As senhas não conferem. Tente novamente.");
                    return;
                }
                plugin.authenticate(player, parts[1], true);
            }
            default -> send(player, "<red>Faça login primeiro: <white>/login <senha> <red>ou <white>/registrar <senha> <senha>");
        }
    }

    private void send(Player player, String mini) {
        player.sendMessage(MM.deserialize(mini));
    }
}
