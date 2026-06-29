package com.redecrystal.chat.commands;

import com.redecrystal.chat.TellService;
import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tell <jogador> <mensagem>} (aliases {@code /msg}, {@code /w}) — send a
 * private message routed network-wide to the target's current server.
 */
public final class TellCommand implements CommandExecutor {

    private final TellService tells;

    public TellCommand(TellService tells) {
        this.tells = tells;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Uso: /tell <jogador> <mensagem>", NamedTextColor.GRAY));
        } else {
            tells.sendTell(p, args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        }
        return true;
    }
}
