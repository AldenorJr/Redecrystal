package com.redecrystal.economy.commands;

import com.redecrystal.economy.gui.BalanceMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /saldo}: GUI-first shortcut — just opens the {@link BalanceMenu}. */
public final class BalanceCommand implements CommandExecutor {

    private final BalanceMenu menu;

    public BalanceCommand(BalanceMenu menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return true;
        }
        menu.open(player);
        return true;
    }
}
