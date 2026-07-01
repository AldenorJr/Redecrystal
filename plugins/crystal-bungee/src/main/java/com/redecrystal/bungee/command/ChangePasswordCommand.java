package com.redecrystal.bungee.command;

import com.redecrystal.bungee.BrandColors;
import com.redecrystal.bungee.listener.ConnectionRoutingListener;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * {@code /trocarsenha <atual> <nova> <confirmar>} (alias {@code /mudarsenha}):
 * lets an authenticated player change their own password from anywhere on the
 * network. Registered on the proxy so it covers every backend server at once and
 * the typed password is never forwarded to (or logged by) a game server. Players
 * still on the login server aren't authenticated yet and are turned away.
 */
public final class ChangePasswordCommand implements SimpleCommand {

    private static final int MIN_PASSWORD_LENGTH = 3;
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final List<String> HINTS = List.of("<senha_atual>", "<nova_senha>", "<confirmar>");

    private final Object plugin;
    private final ProxyServer proxy;
    private final CrystalCore crystal;
    private final Logger logger;

    public ChangePasswordCommand(Object plugin, ProxyServer proxy, CrystalCore crystal, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.crystal = crystal;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        // Only players past the login gate may change a password: anyone still on
        // the login server hasn't proven their identity yet.
        String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
        if (server.isEmpty() || server.equals(ConnectionRoutingListener.LOGIN_SERVER)) {
            player.sendMessage(Component.text("Faça login primeiro.", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 3) {
            player.sendMessage(Component.text(
                    "Uso: /trocarsenha <atual> <nova> <confirmar>", BrandColors.PURPLE_SOFT));
            return;
        }
        String current = args[0];
        String next = args[1];
        String confirm = args[2];
        if (!next.equals(confirm)) {
            player.sendMessage(Component.text("As senhas não conferem.", NamedTextColor.RED));
            return;
        }
        if (next.length() < MIN_PASSWORD_LENGTH || next.length() > MAX_PASSWORD_LENGTH) {
            player.sendMessage(Component.text(
                    "A senha deve ter entre " + MIN_PASSWORD_LENGTH + " e " + MAX_PASSWORD_LENGTH + " caracteres.",
                    NamedTextColor.RED));
            return;
        }
        // HTTP off the caller thread; feedback is a plain message (thread-safe).
        String uuid = player.getUniqueId().toString();
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                crystal.backend().changePassword(uuid, current, next);
                player.sendMessage(Component.text("Senha alterada com sucesso!", NamedTextColor.GREEN));
            } catch (BackendException e) {
                if (e.statusCode() == HTTP_UNAUTHORIZED) {
                    player.sendMessage(Component.text("Senha atual incorreta.", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text(
                            "Não foi possível trocar a senha agora. Tente novamente.", NamedTextColor.RED));
                    logger.warn("changePassword failed for {}: {}", player.getUsername(), e.toString());
                }
            }
        }).schedule();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        int idx = invocation.arguments().length; // position of the arg being typed
        return idx < HINTS.size() ? List.of(HINTS.get(idx)) : List.of();
    }
}
