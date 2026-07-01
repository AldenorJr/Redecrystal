package com.redecrystal.economy.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.redecrystal.core.http.InsufficientFundsException;
import com.redecrystal.economy.CrystalEconomyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /pagar <jogador> <valor>}: transfer Money to another online player. The
 * transfer is atomic on the backend (422 → not enough funds); only online targets
 * are supported in this phase (offline payment is a later feature).
 */
public final class PayCommand implements CommandExecutor {

    private final CrystalEconomyPlugin plugin;
    private final CrystalCore crystal;

    public PayCommand(CrystalEconomyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Uso: /pagar <jogador> <valor>", NamedTextColor.YELLOW));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Valor inválido.", NamedTextColor.RED));
            return true;
        }
        if (amount <= 0) {
            player.sendMessage(Component.text("O valor deve ser positivo.", NamedTextColor.RED));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Jogador não encontrado.", NamedTextColor.RED));
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(Component.text("Você não pode pagar a si mesmo.", NamedTextColor.RED));
            return true;
        }

        String from = player.getUniqueId().toString();
        String to = target.getUniqueId().toString();
        String targetName = target.getName();
        // HTTP off the main thread; player messages are safe from async.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                crystal.economy().transfer(from, to, amount);
                player.sendMessage(Component.text("Você pagou " + amount + " Money para " + targetName + ".",
                        NamedTextColor.GREEN));
                if (target.isOnline()) {
                    target.sendMessage(Component.text(player.getName() + " pagou " + amount + " Money para você.",
                            NamedTextColor.GREEN));
                }
            } catch (InsufficientFundsException e) {
                player.sendMessage(Component.text("Você não tem saldo suficiente.", NamedTextColor.RED));
            } catch (BackendException e) {
                player.sendMessage(Component.text("Não foi possível pagar agora. Tente novamente.",
                        NamedTextColor.RED));
                plugin.getLogger().warning("pay failed for " + player.getName() + ": " + e);
            }
        });
        return true;
    }
}
