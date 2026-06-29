package com.redecrystal.chat.commands;

import com.redecrystal.chat.TellService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /r <mensagem>} — reply to the last person you exchanged a private
 * message with on this server.
 */
public final class ReplyCommand implements CommandExecutor {

    private final TellService tells;

    public ReplyCommand(TellService tells) {
        this.tells = tells;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        String target = tells.lastTarget(p.getUniqueId());
        if (target == null) {
            p.sendMessage(Component.text("Ninguém para responder.", NamedTextColor.RED));
        } else if (args.length == 0) {
            p.sendMessage(Component.text("Uso: /r <mensagem>", NamedTextColor.GRAY));
        } else {
            tells.sendTell(p, target, String.join(" ", args));
        }
        return true;
    }
}
