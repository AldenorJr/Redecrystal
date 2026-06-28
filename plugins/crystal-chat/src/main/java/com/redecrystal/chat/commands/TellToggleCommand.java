package com.redecrystal.chat.commands;

import com.redecrystal.chat.TellService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /telltoggle} — flip whether the player receives private messages, stored
 * network-wide in Redis ({@code tells_disabled}).
 */
public final class TellToggleCommand implements CommandExecutor {

    private final TellService tells;

    public TellToggleCommand(TellService tells) {
        this.tells = tells;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        tells.toggleTells(p);
        return true;
    }
}
