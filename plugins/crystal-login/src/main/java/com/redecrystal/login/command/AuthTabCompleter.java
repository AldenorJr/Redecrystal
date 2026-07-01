package com.redecrystal.login.command;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Static tab hints for the auth commands. The commands themselves are handled by
 * {@code CommandFilterListener} (intercepted and cancelled so the password is
 * never logged); this completer only feeds the client fixed placeholders — it
 * never suggests real data, and in particular never a password.
 */
public final class AuthTabCompleter implements TabCompleter {

    private static final String ADMIN_PERM = "crystal.login.admin";

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("registrar")) {
            if (args.length == 1) return List.of("<senha>");
            if (args.length == 2) return List.of("<repita_a_senha>");
            return List.of();
        }
        // login (and aliases logar/l)
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("<senha>"));
            if (sender.hasPermission(ADMIN_PERM)) {
                out.add("manutencao");
                out.add("setspawn");
            }
            return out;
        }
        if (args.length == 2 && sender.hasPermission(ADMIN_PERM)
                && args[0].equalsIgnoreCase("manutencao")) {
            return List.of("<senha>");
        }
        return List.of();
    }
}
