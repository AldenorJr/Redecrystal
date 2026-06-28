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
