package com.redecrystal.economy.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.redecrystal.core.http.EconomyData;
import com.redecrystal.economy.CrystalEconomyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /eco <give|set> <jogador> <valor>}: admin Money management. {@code give}
 * is an additive delta; {@code set} is an absolute overwrite guarded by the
 * backend optimistic lock (409 → someone changed it first). Online targets only
 * in this phase. Requires {@code crystal.economy.admin}.
 */
public final class EconomyAdminCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.economy.admin";
    private static final int HTTP_CONFLICT = 409;

    private final CrystalEconomyPlugin plugin;
    private final CrystalCore crystal;

    public EconomyAdminCommand(CrystalEconomyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 3 || !(args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("set"))) {
            sender.sendMessage(Component.text("Uso: /eco <give|set> <jogador> <valor>", NamedTextColor.YELLOW));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Valor inválido.", NamedTextColor.RED));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Jogador não encontrado.", NamedTextColor.RED));
            return true;
        }
        boolean give = args[0].equalsIgnoreCase("give");
        String uuid = target.getUniqueId().toString();
        String targetName = target.getName();
        // HTTP off the main thread; feedback messages are safe from async.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (give) {
                    crystal.economy().addMoney(uuid, amount, "admin");
                    sender.sendMessage(Component.text("Você deu " + amount + " Money para " + targetName + ".",
                            NamedTextColor.GREEN));
                } else {
                    EconomyData current = crystal.economy().get(uuid);
                    long tokens = current == null ? 0 : current.tokens();
                    int version = current == null ? 0 : current.version();
                    crystal.economy().set(uuid, amount, tokens, version);
                    sender.sendMessage(Component.text("Money de " + targetName + " definido para " + amount + ".",
                            NamedTextColor.GREEN));
                }
            } catch (BackendException e) {
                if (e.statusCode() == HTTP_CONFLICT) {
                    sender.sendMessage(Component.text("Conflito, tente de novo.", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Não foi possível alterar agora.", NamedTextColor.RED));
                    plugin.getLogger().warning("eco failed on " + targetName + ": " + e);
                }
            }
        });
        return true;
    }
}
